package com.proofly.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofly.common.ErrorCode;
import com.proofly.common.HashChainUtil;
import com.proofly.common.ProoflyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LedgerService {

    public enum Direction { DEBIT, CREDIT }

    public record LineSpec(UUID accountId, Direction direction, long amountMinor, String currency) {}

    public record EntryRequest(
            String idempotencyKey,
            String entryType,
            String reference,
            String description,
            Map<String, Object> metadata,
            UUID reversesEntryId,
            String createdBy,
            List<LineSpec> lines
    ) {}

    private record AccountState(UUID accountId, String accountType, String currency, boolean allowNegative, long balanceMinor) {}

    private final JdbcClient controlJdbc;
    private final ObjectMapper objectMapper;

    public LedgerService(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                         ObjectMapper objectMapper) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.objectMapper = objectMapper;
    }

    public UUID getOrCreateAccount(String code, UUID ownerTenantId, String accountType, String currency, boolean allowNegative) {
        var existing = controlJdbc.sql("SELECT id FROM ledger_accounts WHERE code = :code")
                .param("code", code)
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO ledger_accounts (id, code, owner_tenant_id, account_type, currency, allow_negative)
                VALUES (:id, :code, :tenantId, :type, :currency, :allowNeg)
                ON CONFLICT (code) DO NOTHING
                """)
                .param("id", id)
                .param("code", code)
                .param("tenantId", ownerTenantId)
                .param("type", accountType)
                .param("currency", currency)
                .param("allowNeg", allowNegative)
                .update();

        UUID resolved = controlJdbc.sql("SELECT id FROM ledger_accounts WHERE code = :code")
                .param("code", code)
                .query(UUID.class)
                .single();

        controlJdbc.sql("""
                INSERT INTO account_balances (account_id, balance_minor, currency, version)
                VALUES (:id, 0, :currency, 0)
                ON CONFLICT (account_id) DO NOTHING
                """)
                .param("id", resolved)
                .param("currency", currency)
                .update();

        return resolved;
    }

    @Transactional("controlPlaneTransactionManager")
    public UUID postBalancedEntry(EntryRequest req) {
        var existing = controlJdbc.sql("SELECT id FROM journal_entries WHERE idempotency_key = :key")
                .param("key", req.idempotencyKey())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        if (req.lines() == null || req.lines().size() < 2) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.LEDGER_INVARIANT_VIOLATION,
                    "Journal entry requires at least 2 ledger lines");
        }

        long debits = req.lines().stream()
                .filter(l -> l.direction() == Direction.DEBIT)
                .mapToLong(LineSpec::amountMinor)
                .sum();
        long credits = req.lines().stream()
                .filter(l -> l.direction() == Direction.CREDIT)
                .mapToLong(LineSpec::amountMinor)
                .sum();
        if (debits != credits || debits <= 0) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.LEDGER_INVARIANT_VIOLATION,
                    "Journal entry does not balance: debits=" + debits + " credits=" + credits);
        }

        // Lock account_balances in sorted UUID order to guarantee deadlock freedom
        List<UUID> sortedAccountIds = req.lines().stream()
                .map(LineSpec::accountId)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        Map<UUID, AccountState> lockedStates = new java.util.HashMap<>();
        for (UUID accId : sortedAccountIds) {
            AccountState state = controlJdbc.sql("""
                    SELECT a.id AS account_id, a.account_type, a.currency, a.allow_negative, b.balance_minor
                    FROM ledger_accounts a
                    JOIN account_balances b ON b.account_id = a.id
                    WHERE a.id = :id
                    FOR UPDATE
                    """)
                    .param("id", accId)
                    .query((rs, rowNum) -> new AccountState(
                            rs.getObject("account_id", UUID.class),
                            rs.getString("account_type"),
                            rs.getString("currency"),
                            rs.getBoolean("allow_negative"),
                            rs.getLong("balance_minor")
                    ))
                    .single();
            lockedStates.put(accId, state);
        }

        String prevHash = controlJdbc.sql("""
                SELECT row_hash FROM journal_entries
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .query(String.class)
                .optional()
                .orElse(HashChainUtil.GENESIS_HASH);

        UUID entryId = UUID.randomUUID();
        String metadataJson = toJson(req.metadata() != null ? req.metadata() : Map.of());
        String canonical = String.join("|", entryId.toString(), req.idempotencyKey(), req.entryType(),
                req.reference(), String.valueOf(debits), req.createdBy());
        String rowHash = HashChainUtil.computeRowHash(prevHash, canonical);

        controlJdbc.sql("""
                INSERT INTO journal_entries
                (id, idempotency_key, entry_type, reference, description, metadata, reverses_entry_id,
                 prev_hash, row_hash, created_by)
                VALUES (:id, :ikey, :type, :ref, :desc, CAST(:meta AS JSONB), :reverses, :prevHash, :rowHash, :createdBy)
                """)
                .param("id", entryId)
                .param("ikey", req.idempotencyKey())
                .param("type", req.entryType())
                .param("ref", req.reference())
                .param("desc", req.description())
                .param("meta", metadataJson)
                .param("reverses", req.reversesEntryId())
                .param("prevHash", prevHash)
                .param("rowHash", rowHash)
                .param("createdBy", req.createdBy())
                .update();

        Map<UUID, Long> netDeltas = new java.util.HashMap<>();
        for (LineSpec line : req.lines()) {
            AccountState state = lockedStates.get(line.accountId());
            if (!state.currency().equals(line.currency())) {
                throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.LEDGER_INVARIANT_VIOLATION,
                        "Currency mismatch on account " + line.accountId());
            }

            controlJdbc.sql("""
                    INSERT INTO ledger_lines (id, entry_id, account_id, direction, amount_minor, currency)
                    VALUES (:id, :entryId, :accountId, :direction, :amount, :currency)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("entryId", entryId)
                    .param("accountId", line.accountId())
                    .param("direction", line.direction().name())
                    .param("amount", line.amountMinor())
                    .param("currency", line.currency())
                    .update();

            long signed = signedImpact(state.accountType(), line.direction(), line.amountMinor());
            netDeltas.merge(line.accountId(), signed, Math::addExact);
        }

        for (UUID accId : sortedAccountIds) {
            AccountState state = lockedStates.get(accId);
            long delta = netDeltas.getOrDefault(accId, 0L);
            long nextBalance = Math.addExact(state.balanceMinor(), delta);
            if (!state.allowNegative() && nextBalance < 0) {
                throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.LEDGER_INVARIANT_VIOLATION,
                        "Negative balance disallowed on account " + accId);
            }
            controlJdbc.sql("""
                    UPDATE account_balances
                    SET balance_minor = :bal,
                        version = version + 1,
                        last_entry_id = :entryId,
                        updated_at = NOW()
                    WHERE account_id = :accId
                    """)
                    .param("bal", nextBalance)
                    .param("entryId", entryId)
                    .param("accId", accId)
                    .update();
        }

        return entryId;
    }

    /**
     * Re-verifies cached account_balances against the immutable ledger_lines source of truth.
     * Uses the latest balance_checkpoint if present so verification stays O(delta) at scale.
     */
    public long verifyAccountAgainstLedger(UUID accountId) {
        String accountType = controlJdbc.sql("SELECT account_type FROM ledger_accounts WHERE id = :id")
                .param("id", accountId)
                .query(String.class)
                .single();

        long cached = controlJdbc.sql("SELECT balance_minor FROM account_balances WHERE account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();

        long ledgerSum = computeLedgerBalanceFromCheckpoint(accountId, accountType);
        if (cached != ledgerSum) {
            throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.LEDGER_INVARIANT_VIOLATION,
                    "Account balance cache mismatch for account " + accountId + ": cache=" + cached + " ledger=" + ledgerSum);
        }
        return ledgerSum;
    }

    public long computeLedgerBalanceFromCheckpoint(UUID accountId, String accountType) {
        var cpOpt = controlJdbc.sql("""
                SELECT bc.verified_balance_minor, je.created_at, je.id
                FROM balance_checkpoints bc
                JOIN journal_entries je ON je.id = bc.as_of_entry_id
                WHERE bc.account_id = :accId
                ORDER BY bc.created_at DESC
                LIMIT 1
                """)
                .param("accId", accountId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getLong("verified_balance_minor"),
                        rs.getTimestamp("created_at"),
                        rs.getObject("id", UUID.class)
                })
                .optional();

        long baseBalance = 0L;
        java.sql.Timestamp afterTs = null;
        if (cpOpt.isPresent()) {
            baseBalance = (Long) cpOpt.get()[0];
            afterTs = (java.sql.Timestamp) cpOpt.get()[1];
        }

        String sql = """
                SELECT COALESCE(SUM(
                    CASE
                        WHEN :accType IN ('ASSET', 'EXPENSE') THEN
                            CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END
                        ELSE
                            CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE -l.amount_minor END
                    END
                ), 0)
                FROM ledger_lines l
                JOIN journal_entries je ON je.id = l.entry_id
                WHERE l.account_id = :accId
                """ + (afterTs != null ? " AND je.created_at > :afterTs " : "");

        var query = controlJdbc.sql(sql)
                .param("accType", accountType)
                .param("accId", accountId);
        if (afterTs != null) {
            query = query.param("afterTs", afterTs);
        }
        long delta = query.query(Long.class).single();
        return Math.addExact(baseBalance, delta);
    }

    public long readCachedBalance(String accountCode) {
        return controlJdbc.sql("""
                SELECT COALESCE(b.balance_minor, 0)
                FROM ledger_accounts a
                JOIN account_balances b ON b.account_id = a.id
                WHERE a.code = :code
                """)
                .param("code", accountCode)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public boolean recordCheckpointIfNewEntries(UUID accountId, long verifiedBalanceMinor) {
        var latestEntryId = controlJdbc.sql("""
                SELECT l.entry_id
                FROM ledger_lines l
                JOIN journal_entries je ON je.id = l.entry_id
                WHERE l.account_id = :accId
                ORDER BY je.created_at DESC, je.id DESC
                LIMIT 1
                """)
                .param("accId", accountId)
                .query(UUID.class)
                .optional();

        if (latestEntryId.isEmpty()) {
            return false;
        }

        UUID entryId = latestEntryId.get();
        var existing = controlJdbc.sql("""
                SELECT as_of_entry_id
                FROM balance_checkpoints
                WHERE account_id = :accId
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("accId", accountId)
                .query(UUID.class)
                .optional();

        if (existing.isPresent() && existing.get().equals(entryId)) {
            return false;
        }

        controlJdbc.sql("""
                INSERT INTO balance_checkpoints (id, account_id, as_of_entry_id, verified_balance_minor)
                VALUES (:id, :accId, :entryId, :bal)
                """)
                .param("id", UUID.randomUUID())
                .param("accId", accountId)
                .param("entryId", entryId)
                .param("bal", verifiedBalanceMinor)
                .update();
        return true;
    }

    private static long signedImpact(String accountType, Direction direction, long amount) {
        boolean debitIncreases = "ASSET".equals(accountType) || "EXPENSE".equals(accountType);
        if (debitIncreases) {
            return direction == Direction.DEBIT ? amount : -amount;
        }
        return direction == Direction.CREDIT ? amount : -amount;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ledger metadata", e);
        }
    }
}
