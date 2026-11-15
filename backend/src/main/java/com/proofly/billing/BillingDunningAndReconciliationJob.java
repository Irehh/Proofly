package com.proofly.billing;

import com.proofly.audit.AuditWriter;
import com.proofly.notification.NotificationOutboxService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class BillingDunningAndReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(BillingDunningAndReconciliationJob.class);

    private final JdbcClient controlJdbc;
    private final TransactionTemplate controlTx;
    private final LedgerService ledgerService;
    private final PaymentGatewayRouter gatewayRouter;
    private final SubscriptionAndBillingService billingService;
    private final BillingProperties billingProps;
    private final AuditWriter auditWriter;
    private final NotificationOutboxService outboxService;

    public BillingDunningAndReconciliationJob(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                              @Qualifier("controlPlaneTransactionTemplate") TransactionTemplate controlTx,
                                              LedgerService ledgerService,
                                              PaymentGatewayRouter gatewayRouter,
                                              SubscriptionAndBillingService billingService,
                                              BillingProperties billingProps,
                                              AuditWriter auditWriter,
                                              NotificationOutboxService outboxService) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.controlTx = controlTx;
        this.ledgerService = ledgerService;
        this.gatewayRouter = gatewayRouter;
        this.billingService = billingService;
        this.billingProps = billingProps;
        this.auditWriter = auditWriter;
        this.outboxService = outboxService;
    }

    /**
     * Atomically suspends expired grace-period subscriptions and notifies tenant admins.
     */
    @Scheduled(fixedDelayString = "${proofly.billing.dunning-interval:PT15M}")
    @SchedulerLock(name = "enforceBillingGraceAndSuspension", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")
    public void enforceGraceAndSuspension() {
        List<UUID> expiredTenantIds = controlTx.execute(status -> {
            List<UUID> ids = controlJdbc.sql("""
                    SELECT tenant_id
                    FROM subscriptions
                    WHERE status = 'GRACE' AND grace_until < NOW()
                    FOR UPDATE SKIP LOCKED
                    """)
                    .query(UUID.class)
                    .list();

            for (UUID tid : ids) {
                controlJdbc.sql("""
                        UPDATE subscriptions
                        SET status = 'SUSPENDED', updated_at = NOW()
                        WHERE tenant_id = :tid AND status = 'GRACE' AND grace_until < NOW()
                        """)
                        .param("tid", tid)
                        .update();

                controlJdbc.sql("""
                        UPDATE tenants
                        SET status = 'SUSPENDED', updated_at = NOW()
                        WHERE id = :tid AND status <> 'SUSPENDED'
                        """)
                        .param("tid", tid)
                        .update();

                auditWriter.writePlatformEvent(
                        "billing-dunning",
                        "SYSTEM",
                        tid,
                        "SUBSCRIPTION_SUSPENDED",
                        "tenant",
                        tid.toString(),
                        Map.of("status", "GRACE"),
                        Map.of("status", "SUSPENDED"),
                        "job"
                );

                String orgName = controlJdbc.sql("SELECT name FROM tenants WHERE id = :tid")
                        .param("tid", tid).query(String.class).optional().orElse("your workspace");
                String contactEmail = controlJdbc.sql("""
                        SELECT email FROM global_user_directory
                        WHERE tenant_id = :tid AND email IS NOT NULL AND status = 'ACTIVE'
                        ORDER BY created_at ASC
                        LIMIT 1
                        """)
                        .param("tid", tid)
                        .query(String.class)
                        .optional()
                        .orElse(null);

                if (contactEmail != null && !contactEmail.isBlank()) {
                    outboxService.enqueueEmail(
                            tid,
                            contactEmail,
                            "SUBSCRIPTION_SUSPENDED",
                            "Proofly workspace suspended",
                            Map.of("orgName", orgName),
                            "suspend:" + tid + ":" + Instant.now().truncatedTo(ChronoUnit.DAYS)
                    );
                }
                log.warn("Suspended tenant={} after billing grace period expired", tid);
            }
            return ids;
        });

        if (expiredTenantIds != null && !expiredTenantIds.isEmpty()) {
            log.info("Billing dunning suspended {} expired grace-period tenant(s)", expiredTenantIds.size());
        }
    }

    /**
     * Sweeps INITIATED payments whose webhooks may have been delayed or missed,
     * verifying directly with the payment's routed gateway and settling or expiring idempotently.
     */
    @Scheduled(fixedDelayString = "${proofly.billing.pending-sweep-interval:PT5M}")
    @SchedulerLock(name = "reconcilePendingCheckouts", lockAtLeastFor = "PT30S", lockAtMostFor = "PT10M")
    public void reconcilePendingCheckouts() {
        int minMinutes = Math.max(1, billingProps.pendingSweepMinMinutes());
        int maxHours = Math.max(1, billingProps.pendingSweepMaxHours());
        int batchSize = Math.max(1, billingProps.pendingSweepBatchSize());

        record PendingPayment(SubscriptionAndBillingService.PaymentRow payment, Instant initiatedAt) {}

        List<PendingPayment> pending = controlJdbc.sql("""
                SELECT id, tenant_id, target_plan_id, billing_cycle, paystack_reference,
                       gateway, provider_session_id, amount_minor, currency, status, initiated_at
                FROM payments
                WHERE status = 'INITIATED'
                  AND initiated_at < NOW() - (:minMinutes * INTERVAL '1 minute')
                ORDER BY initiated_at ASC
                LIMIT :limit
                """)
                .param("minMinutes", minMinutes)
                .param("limit", batchSize)
                .query((rs, rowNum) -> new PendingPayment(
                        SubscriptionAndBillingService.mapPaymentRow(rs),
                        rs.getTimestamp("initiated_at").toInstant()
                ))
                .list();

        Instant expireCutoff = Instant.now().minus(maxHours, ChronoUnit.HOURS);

        for (PendingPayment entry : pending) {
            var payment = entry.payment();
            try {
                PaymentGateway gateway = gatewayRouter.byCode(payment.gateway());
                PaymentGateway.VerifyResult verified = gateway.verifyTransaction(
                        payment.reference(), payment.providerSessionId());

                switch (verified.normalizedStatus()) {
                    case SUCCEEDED -> {
                        if (SubscriptionAndBillingService.amountsAndCurrencyMatch(
                                payment, verified.amountMinor(), verified.currency())) {
                            controlTx.executeWithoutResult(tx -> billingService.settleSuccessfulPaymentInTx(
                                    payment.id(),
                                    verified.payerEmail(),
                                    "pending-checkout-sweep"
                            ));
                            log.info("Reconciled missed webhook for payment={} via gateway={}",
                                    payment.reference(), payment.gateway());
                        } else {
                            billingService.recordMismatchIssue(
                                    payment, verified.amountMinor(), verified.currency(), "pending-checkout-sweep");
                        }
                    }
                    case FAILED, ABANDONED -> billingService.markPaymentAbandoned(
                            payment.id(), "gateway-" + verified.normalizedStatus().name().toLowerCase());
                    case PENDING -> {
                        if (entry.initiatedAt().isBefore(expireCutoff)) {
                            billingService.markPaymentAbandoned(payment.id(), "expired-after-" + maxHours + "h");
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Pending checkout sweep failed for ref={} gateway={}: {}",
                        payment.reference(), payment.gateway(), ex.getMessage());
            }
        }
    }

    /**
     * Verifies cached account_balances against immutable ledger_lines from the latest checkpoint,
     * deduplicating OPEN alerts and advancing balance_checkpoints when verified.
     */
    @Scheduled(fixedDelayString = "${proofly.billing.reconcile-interval:PT1H}")
    @SchedulerLock(name = "reconcileLedgerBalances", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void reconcileAccountBalances() {
        List<Object[]> accounts = controlJdbc.sql("""
                SELECT a.id, a.account_type, a.currency, b.balance_minor
                FROM ledger_accounts a
                JOIN account_balances b ON b.account_id = a.id
                """)
                .query((rs, rowNum) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("account_type"),
                        rs.getString("currency"),
                        rs.getLong("balance_minor")
                })
                .list();

        for (Object[] row : accounts) {
            UUID accId = (UUID) row[0];
            String type = (String) row[1];
            String currency = ((String) row[2]).trim();
            long cached = (Long) row[3];
            long ledgerSum = ledgerService.computeLedgerBalanceFromCheckpoint(accId, type);

            if (cached != ledgerSum) {
                log.error("Ledger reconciliation discrepancy account={} cached={} ledger={}", accId, cached, ledgerSum);
                controlTx.executeWithoutResult(status -> controlJdbc.sql("""
                        INSERT INTO reconciliation_issues
                        (id, issue_type, account_id, expected_minor, actual_minor, currency, details_jsonb, status)
                        VALUES (:id, 'BALANCE_MISMATCH', :accId, :exp, :act, :cur, '{}'::jsonb, 'OPEN')
                        ON CONFLICT (issue_type, account_id) WHERE status = 'OPEN' AND account_id IS NOT NULL
                        DO UPDATE SET expected_minor = EXCLUDED.expected_minor,
                                      actual_minor = EXCLUDED.actual_minor,
                                      detected_at = NOW()
                        """)
                        .param("id", UUID.randomUUID())
                        .param("accId", accId)
                        .param("exp", ledgerSum)
                        .param("act", cached)
                        .param("cur", currency)
                        .update());
            } else {
                controlTx.executeWithoutResult(status -> ledgerService.recordCheckpointIfNewEntries(accId, ledgerSum));
            }
        }
    }
}
