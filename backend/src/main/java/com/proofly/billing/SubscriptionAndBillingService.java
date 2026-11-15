package com.proofly.billing;

import com.proofly.audit.AuditWriter;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.notification.NotificationOutboxService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionAndBillingService {

    public record PlanPriceView(
            String currency,
            GatewayCode gateway,
            long monthlyPriceMinor,
            long annualPriceMinor
    ) {}

    public record PlanView(
            UUID id,
            String code,
            String name,
            int maxMembers,
            int maxSites,
            long monthlyPriceMinor,
            long annualPriceMinor,
            String currency,
            GatewayCode gateway,
            boolean isActive,
            List<PlanPriceView> prices
    ) {}

    public record CurrencyBalanceView(
            String currency,
            GatewayCode gateway,
            long creditBalanceMinor
    ) {}

    public record PaymentReceiptView(
            UUID id,
            String reference,
            String planCode,
            String planName,
            String billingCycle,
            GatewayCode gateway,
            long amountMinor,
            String currency,
            String status,
            Instant initiatedAt,
            Instant settledAt
    ) {}

    public record SubscriptionView(
            UUID tenantId,
            String planCode,
            String planName,
            String billingCycle,
            String status,
            Instant currentPeriodEnd,
            Instant graceUntil,
            long creditBalanceMinor,
            String currency,
            GatewayCode gateway,
            List<CurrencyBalanceView> currencyBalances
    ) {}

    public record CheckoutResponse(
            UUID paymentId,
            GatewayCode gateway,
            String reference,
            String authorizationUrl,
            long amountMinor,
            String currency
    ) {}

    public record VerifyPaymentResponse(
            GatewayCode gateway,
            String reference,
            String localStatus,
            String providerStatus,
            long amountMinor,
            String currency
    ) {}

    record PaymentRow(
            UUID id,
            UUID tenantId,
            UUID targetPlanId,
            String billingCycle,
            String reference,
            GatewayCode gateway,
            String providerSessionId,
            long amountMinor,
            String currency,
            String status
    ) {}

    private record BasePlanRow(
            UUID id,
            String code,
            String name,
            int maxMembers,
            int maxSites,
            long monthlyPriceMinor,
            long annualPriceMinor,
            String currency,
            boolean isActive
    ) {}

    private final JdbcClient controlJdbc;
    private final TransactionTemplate controlTx;
    private final LedgerService ledgerService;
    private final PaymentGatewayRouter gatewayRouter;
    private final BillingProperties billingProps;
    private final AuditWriter auditWriter;
    private final NotificationOutboxService outboxService;

    public SubscriptionAndBillingService(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                         @Qualifier("controlPlaneTransactionTemplate") TransactionTemplate controlTx,
                                         LedgerService ledgerService,
                                         PaymentGatewayRouter gatewayRouter,
                                         BillingProperties billingProps,
                                         AuditWriter auditWriter,
                                         NotificationOutboxService outboxService) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.controlTx = controlTx;
        this.ledgerService = ledgerService;
        this.gatewayRouter = gatewayRouter;
        this.billingProps = billingProps;
        this.auditWriter = auditWriter;
        this.outboxService = outboxService;
    }

    public List<PlanView> listPlans(String requestedCurrency) {
        String targetCurrency = gatewayRouter.resolveCurrencyOrDefault(requestedCurrency);
        GatewayCode defaultGateway = gatewayRouter.gatewayCodeForCurrency(targetCurrency);

        List<BasePlanRow> basePlans = controlJdbc.sql("""
                SELECT id, code, name, max_members, max_sites, monthly_price_minor, annual_price_minor, currency, is_active
                FROM plans
                WHERE is_active = TRUE
                ORDER BY monthly_price_minor ASC
                """)
                .query(BasePlanRow.class)
                .list();

        record RawPriceRow(UUID planId, String currency, String gateway, long monthlyMinor, long annualMinor) {}
        List<RawPriceRow> allPrices = controlJdbc.sql("""
                SELECT plan_id, currency, gateway, monthly_price_minor, annual_price_minor
                FROM plan_prices
                WHERE is_active = TRUE
                ORDER BY currency ASC
                """)
                .query(RawPriceRow.class)
                .list();

        Map<UUID, List<PlanPriceView>> pricesByPlan = new LinkedHashMap<>();
        for (RawPriceRow row : allPrices) {
            pricesByPlan.computeIfAbsent(row.planId(), k -> new ArrayList<>())
                    .add(new PlanPriceView(
                            row.currency().trim(),
                            GatewayCode.fromString(row.gateway()),
                            row.monthlyMinor(),
                            row.annualMinor()
                    ));
        }

        List<PlanView> result = new ArrayList<>(basePlans.size());
        for (BasePlanRow plan : basePlans) {
            List<PlanPriceView> planPrices = pricesByPlan.getOrDefault(plan.id(), List.of());
            PlanPriceView selected = planPrices.stream()
                    .filter(p -> p.currency().equalsIgnoreCase(targetCurrency))
                    .findFirst()
                    .orElse(new PlanPriceView(
                            plan.currency().trim(),
                            defaultGateway,
                            plan.monthlyPriceMinor(),
                            plan.annualPriceMinor()
                    ));

            result.add(new PlanView(
                    plan.id(),
                    plan.code(),
                    plan.name(),
                    plan.maxMembers(),
                    plan.maxSites(),
                    selected.monthlyPriceMinor(),
                    selected.annualPriceMinor(),
                    selected.currency(),
                    selected.gateway(),
                    plan.isActive(),
                    planPrices
            ));
        }
        return result;
    }

    public SubscriptionView getTenantSubscription(UUID tenantId) {
        List<CurrencyBalanceView> currencyBalances = readAllTenantCreditBalances(tenantId);

        var subOpt = controlJdbc.sql("""
                SELECT s.tenant_id, p.code AS plan_code, p.name AS plan_name, s.billing_cycle,
                       s.status, s.current_period_end, s.grace_until, s.currency, s.gateway
                FROM subscriptions s
                JOIN plans p ON p.id = s.plan_id
                WHERE s.tenant_id = :tid
                ORDER BY s.updated_at DESC
                LIMIT 1
                """)
                .param("tid", tenantId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("plan_code"),
                        rs.getString("plan_name"),
                        rs.getString("billing_cycle"),
                        rs.getString("status"),
                        rs.getTimestamp("current_period_end").toInstant(),
                        rs.getTimestamp("grace_until") != null ? rs.getTimestamp("grace_until").toInstant() : null,
                        rs.getString("currency"),
                        rs.getString("gateway")
                })
                .optional();

        if (subOpt.isPresent()) {
            var r = subOpt.get();
            String currency = PaymentGatewayRouter.normalizeCurrency((String) r[7]);
            GatewayCode gateway = GatewayCode.fromString((String) r[8]);
            long credit = readTenantCreditBalance(tenantId, currency);
            return new SubscriptionView((UUID) r[0], (String) r[1], (String) r[2], (String) r[3],
                    (String) r[4], (Instant) r[5], (Instant) r[6], credit, currency, gateway, currencyBalances);
        }

        var fallback = controlJdbc.sql("""
                SELECT p.code, p.name, t.default_currency
                FROM tenants t
                JOIN plans p ON p.id = t.current_plan_id
                WHERE t.id = :tid
                """)
                .param("tid", tenantId)
                .query((rs, rowNum) -> new Object[]{
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("default_currency")
                })
                .single();

        String currency = PaymentGatewayRouter.normalizeCurrency((String) fallback[2]);
        GatewayCode gateway = gatewayRouter.gatewayCodeForCurrency(currency);
        long credit = readTenantCreditBalance(tenantId, currency);
        return new SubscriptionView(tenantId, (String) fallback[0], (String) fallback[1], "MONTHLY",
                "ACTIVE", null, null, credit, currency, gateway, currencyBalances);
    }

    public List<PaymentReceiptView> listTenantPayments(UUID tenantId, int limit) {
        int clamped = Math.min(100, Math.max(1, limit));
        return controlJdbc.sql("""
                SELECT pay.id, pay.paystack_reference, p.code AS plan_code, p.name AS plan_name,
                       pay.billing_cycle, pay.gateway, pay.amount_minor, pay.currency,
                       pay.status, pay.initiated_at, pay.settled_at
                FROM payments pay
                JOIN plans p ON p.id = pay.target_plan_id
                WHERE pay.tenant_id = :tid
                ORDER BY pay.initiated_at DESC
                LIMIT :limit
                """)
                .param("tid", tenantId)
                .param("limit", clamped)
                .query((rs, rowNum) -> new PaymentReceiptView(
                        rs.getObject("id", UUID.class),
                        rs.getString("paystack_reference"),
                        rs.getString("plan_code"),
                        rs.getString("plan_name"),
                        rs.getString("billing_cycle"),
                        GatewayCode.fromString(rs.getString("gateway")),
                        rs.getLong("amount_minor"),
                        rs.getString("currency").trim(),
                        rs.getString("status"),
                        rs.getTimestamp("initiated_at").toInstant(),
                        rs.getTimestamp("settled_at") != null ? rs.getTimestamp("settled_at").toInstant() : null
                ))
                .list();
    }

    public CheckoutResponse initiateCheckout(UUID tenantId,
                                             String payerEmail,
                                             String planCode,
                                             String billingCycle,
                                             String requestedCurrency,
                                             String idempotencyKey) {
        String tenantDefaultCurrency = controlJdbc.sql("SELECT default_currency FROM tenants WHERE id = :tid")
                .param("tid", tenantId)
                .query(String.class)
                .optional()
                .orElse(gatewayRouter.defaultCurrency());

        String currency = gatewayRouter.resolveCurrencyOrDefault(
                requestedCurrency != null && !requestedCurrency.isBlank() ? requestedCurrency : tenantDefaultCurrency
        );
        PaymentGateway gateway = gatewayRouter.routeForCurrency(currency);

        var existing = controlJdbc.sql("""
                SELECT id, paystack_reference, gateway, provider_session_id, amount_minor, currency
                FROM payments
                WHERE idempotency_key = :ikey
                """)
                .param("ikey", idempotencyKey)
                .query((rs, rowNum) -> new Object[]{
                        rs.getObject("id", UUID.class),
                        rs.getString("paystack_reference"),
                        rs.getString("gateway"),
                        rs.getString("provider_session_id"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency")
                })
                .optional();

        BasePlanRow plan = controlJdbc.sql("""
                SELECT id, code, name, max_members, max_sites, monthly_price_minor,
                       annual_price_minor, currency, is_active
                FROM plans
                WHERE code = :code AND is_active = TRUE
                """)
                .param("code", planCode.trim().toUpperCase(Locale.ROOT))
                .query(BasePlanRow.class)
                .optional()
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                        "Plan not found or inactive: " + planCode));

        PlanPriceView resolvedPrice = controlJdbc.sql("""
                SELECT currency, gateway, monthly_price_minor, annual_price_minor
                FROM plan_prices
                WHERE plan_id = :planId AND currency = :currency AND is_active = TRUE
                """)
                .param("planId", plan.id())
                .param("currency", currency)
                .query((rs, rowNum) -> new PlanPriceView(
                        rs.getString("currency").trim(),
                        GatewayCode.fromString(rs.getString("gateway")),
                        rs.getLong("monthly_price_minor"),
                        rs.getLong("annual_price_minor")
                ))
                .optional()
                .orElseThrow(() -> new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                        "Plan " + plan.code() + " is not priced in currency " + currency));

        boolean annual = "ANNUAL".equalsIgnoreCase(billingCycle);
        long amountMinor = annual ? resolvedPrice.annualPriceMinor() : resolvedPrice.monthlyPriceMinor();
        if (amountMinor <= 0) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Selected plan does not require gateway checkout");
        }

        UUID paymentId;
        String reference;
        GatewayCode effectiveGateway = gateway.gatewayCode();

        if (existing.isPresent()) {
            paymentId = (UUID) existing.get()[0];
            reference = (String) existing.get()[1];
            effectiveGateway = GatewayCode.fromString((String) existing.get()[2]);
            amountMinor = (Long) existing.get()[4];
            currency = ((String) existing.get()[5]).trim();
            gateway = gatewayRouter.byCode(effectiveGateway);
        } else {
            paymentId = UUID.randomUUID();
            reference = "prf_" + paymentId.toString().replace("-", "").substring(0, 20);
            final long finalAmount = amountMinor;
            final String finalCurrency = currency;
            final GatewayCode finalGateway = effectiveGateway;
            controlTx.executeWithoutResult(status -> controlJdbc.sql("""
                    INSERT INTO payments
                    (id, tenant_id, target_plan_id, billing_cycle, paystack_reference, gateway,
                     amount_minor, currency, status, idempotency_key)
                    VALUES (:id, :tid, :planId, :cycle, :ref, :gateway, :amount, :currency, 'INITIATED', :ikey)
                    """)
                    .param("id", paymentId)
                    .param("tid", tenantId)
                    .param("planId", plan.id())
                    .param("cycle", annual ? "ANNUAL" : "MONTHLY")
                    .param("ref", reference)
                    .param("gateway", finalGateway.name())
                    .param("amount", finalAmount)
                    .param("currency", finalCurrency)
                    .param("ikey", idempotencyKey)
                    .update());
        }

        PaymentGateway.CheckoutInitResult init = gateway.initializeCheckout(
                new PaymentGateway.CheckoutInitRequest(
                        tenantId,
                        payerEmail,
                        plan.code(),
                        plan.name(),
                        annual ? "ANNUAL" : "MONTHLY",
                        amountMinor,
                        currency,
                        reference
                )
        );

        if (init.providerSessionId() != null && !init.providerSessionId().isBlank()) {
            controlJdbc.sql("UPDATE payments SET provider_session_id = :sid WHERE id = :id AND provider_session_id IS NULL")
                    .param("sid", init.providerSessionId())
                    .param("id", paymentId)
                    .update();
        }

        return new CheckoutResponse(paymentId, effectiveGateway, init.reference(), init.authorizationUrl(), amountMinor, currency);
    }

    public VerifyPaymentResponse verifyPaymentStatus(UUID tenantId, String reference) {
        PaymentRow payment = findPaymentForTenant(tenantId, reference);
        String providerStatus = payment.status();
        String localStatus = payment.status();

        if ("INITIATED".equals(payment.status())) {
            PaymentGateway gateway = gatewayRouter.byCode(payment.gateway());
            PaymentGateway.VerifyResult verified = gateway.verifyTransaction(reference, payment.providerSessionId());
            providerStatus = verified.rawProviderStatus();

            if (verified.normalizedStatus() == PaymentGateway.NormalizedStatus.SUCCEEDED) {
                if (amountsAndCurrencyMatch(payment, verified.amountMinor(), verified.currency())) {
                    controlTx.executeWithoutResult(tx -> settleSuccessfulPaymentInTx(
                            payment.id(),
                            verified.payerEmail(),
                            "gateway-verify"
                    ));
                    localStatus = "SUCCEEDED";
                } else {
                    recordMismatchIssue(payment, verified.amountMinor(), verified.currency(), "verify");
                }
            } else if (verified.normalizedStatus() == PaymentGateway.NormalizedStatus.ABANDONED) {
                markPaymentAbandoned(payment.id(), "verify");
                localStatus = "ABANDONED";
            }
        }

        return new VerifyPaymentResponse(
                payment.gateway(),
                reference,
                localStatus,
                providerStatus,
                payment.amountMinor(),
                payment.currency()
        );
    }

    public void processGatewayWebhook(GatewayCode gatewayCode, byte[] rawBody, String signatureHeader) {
        PaymentGateway gateway = gatewayRouter.byCode(gatewayCode);
        if (!gateway.isValidWebhookSignature(rawBody, signatureHeader)) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_WEBHOOK_SIGNATURE,
                    "Invalid " + gatewayCode + " webhook signature");
        }

        PaymentGateway.ParsedWebhookEvent event = gateway.parseWebhookEvent(rawBody);
        controlTx.executeWithoutResult(status -> processParsedWebhookInTx(event));
    }

    private void processParsedWebhookInTx(PaymentGateway.ParsedWebhookEvent event) {
        int inserted = controlJdbc.sql("""
                INSERT INTO payment_events
                (id, provider_event_id, paystack_reference, gateway, event_type, signature_valid, payload_jsonb, status)
                VALUES (:id, :eid, :ref, :gateway, :type, TRUE, CAST(:payload AS JSONB), 'RECEIVED')
                ON CONFLICT (provider_event_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("eid", event.providerEventId())
                .param("ref", event.reference())
                .param("gateway", event.gateway().name())
                .param("type", event.eventType())
                .param("payload", event.rawPayload())
                .update();

        if (inserted == 0) {
            return;
        }

        PaymentRow payment = controlJdbc.sql("""
                SELECT id, tenant_id, target_plan_id, billing_cycle, paystack_reference,
                       gateway, provider_session_id, amount_minor, currency, status
                FROM payments
                WHERE paystack_reference = :ref
                FOR UPDATE
                """)
                .param("ref", event.reference())
                .query((rs, rowNum) -> mapPaymentRow(rs))
                .optional()
                .orElse(null);

        if (payment == null) {
            markEventStatus(event.providerEventId(), "IGNORED");
            return;
        }

        if (event.outcome() == PaymentGateway.WebhookOutcome.SUCCEEDED) {
            if (!amountsAndCurrencyMatch(payment, event.amountMinor(), event.currency())) {
                recordMismatchIssue(payment, event.amountMinor(), event.currency(), "webhook:" + event.gateway().name());
                markEventStatus(event.providerEventId(), "FAILED");
                throw new ProoflyException(HttpStatus.CONFLICT, ErrorCode.LEDGER_INVARIANT_VIOLATION,
                        "Webhook amount/currency mismatch");
            }

            if (!"SUCCEEDED".equals(payment.status())) {
                settleLockedPayment(payment, event.payerEmail(), event.gateway().name().toLowerCase(Locale.ROOT) + "-webhook");
            }
            markEventStatus(event.providerEventId(), "PROCESSED");
        } else if (event.outcome() == PaymentGateway.WebhookOutcome.FAILED) {
            handleFailedPayment(payment, event.payerEmail());
            markEventStatus(event.providerEventId(), "PROCESSED");
        } else {
            markEventStatus(event.providerEventId(), "IGNORED");
        }
    }

    void settleSuccessfulPaymentInTx(UUID paymentId, String payerEmail, String source) {
        PaymentRow locked = controlJdbc.sql("""
                SELECT id, tenant_id, target_plan_id, billing_cycle, paystack_reference,
                       gateway, provider_session_id, amount_minor, currency, status
                FROM payments
                WHERE id = :id
                FOR UPDATE
                """)
                .param("id", paymentId)
                .query((rs, rowNum) -> mapPaymentRow(rs))
                .optional()
                .orElse(null);
        if (locked != null && !"SUCCEEDED".equals(locked.status())) {
            settleLockedPayment(locked, payerEmail, source);
        }
    }

    void markPaymentAbandoned(UUID paymentId, String reasonSource) {
        controlTx.executeWithoutResult(status -> {
            int updated = controlJdbc.sql("""
                    UPDATE payments SET status = 'ABANDONED'
                    WHERE id = :id AND status = 'INITIATED'
                    """)
                    .param("id", paymentId)
                    .update();
            if (updated > 0) {
                auditWriter.writePlatformEvent("billing-reconciler", "SYSTEM", null, "PAYMENT_MARKED_ABANDONED",
                        "payment", paymentId.toString(), Map.of("status", "INITIATED"),
                        Map.of("status", "ABANDONED", "source", reasonSource), "job");
            }
        });
    }

    void recordMismatchIssue(PaymentRow payment, long actualMinor, String actualCurrency, String source) {
        controlTx.executeWithoutResult(status -> controlJdbc.sql("""
                INSERT INTO reconciliation_issues
                (id, issue_type, payment_id, expected_minor, actual_minor, currency, details_jsonb, status)
                VALUES (:id, 'GATEWAY_MISMATCH', :pid, :exp, :act, :cur, CAST(:details AS JSONB), 'OPEN')
                """)
                .param("id", UUID.randomUUID())
                .param("pid", payment.id())
                .param("exp", payment.amountMinor())
                .param("act", actualMinor)
                .param("cur", payment.currency())
                .param("details", "{\"reference\":\"" + payment.reference() + "\",\"gateway\":\"" + payment.gateway().name()
                        + "\",\"actualCurrency\":\"" + actualCurrency + "\",\"source\":\"" + source + "\"}")
                .update());
    }

    static boolean amountsAndCurrencyMatch(PaymentRow payment, long actualMinor, String actualCurrency) {
        return actualMinor == payment.amountMinor()
                && actualCurrency != null
                && payment.currency().trim().equalsIgnoreCase(actualCurrency.trim());
    }

    private void settleLockedPayment(PaymentRow payment, String payerEmail, String source) {
        String currency = payment.currency().trim().toUpperCase(Locale.ROOT);
        String gatewayLower = payment.gateway().name().toLowerCase(Locale.ROOT);

        UUID clearingAccount = ledgerService.getOrCreateAccount(
                "platform:clearing:" + payment.gateway().name() + ":" + currency,
                null, "ASSET", currency, true);
        UUID revenueAccount = ledgerService.getOrCreateAccount(
                "platform:revenue:subscriptions:" + currency,
                null, "REVENUE", currency, false);
        UUID tenantCreditAccount = ledgerService.getOrCreateAccount(
                "tenant:" + payment.tenantId() + ":credit:" + currency,
                payment.tenantId(), "LIABILITY", currency, false);

        if (payment.amountMinor() >= billingProps.majorOpReverifyThresholdMinor()) {
            ledgerService.verifyAccountAgainstLedger(tenantCreditAccount);
        }

        // 1. Record settlement from gateway clearing into tenant credit
        ledgerService.postBalancedEntry(new LedgerService.EntryRequest(
                gatewayLower + ":settle:" + payment.reference(),
                "SETTLEMENT",
                payment.reference(),
                payment.gateway().name() + " settlement (" + currency + ") for tenant " + payment.tenantId(),
                Map.of("paymentId", payment.id().toString(), "gateway", payment.gateway().name(), "currency", currency),
                null,
                source,
                List.of(
                        new LedgerService.LineSpec(clearingAccount, LedgerService.Direction.DEBIT, payment.amountMinor(), currency),
                        new LedgerService.LineSpec(tenantCreditAccount, LedgerService.Direction.CREDIT, payment.amountMinor(), currency)
                )
        ));

        // 2. Recognize subscription revenue from tenant credit
        ledgerService.postBalancedEntry(new LedgerService.EntryRequest(
                gatewayLower + ":charge:" + payment.reference(),
                "CHARGE",
                payment.reference(),
                "Subscription activation charge for plan " + payment.targetPlanId(),
                Map.of("planId", payment.targetPlanId().toString(), "cycle", payment.billingCycle(), "gateway", payment.gateway().name()),
                null,
                source,
                List.of(
                        new LedgerService.LineSpec(tenantCreditAccount, LedgerService.Direction.DEBIT, payment.amountMinor(), currency),
                        new LedgerService.LineSpec(revenueAccount, LedgerService.Direction.CREDIT, payment.amountMinor(), currency)
                )
        ));

        controlJdbc.sql("UPDATE payments SET status = 'SUCCEEDED', settled_at = NOW() WHERE id = :id")
                .param("id", payment.id())
                .update();

        Instant now = Instant.now();
        Instant periodEnd = "ANNUAL".equals(payment.billingCycle())
                ? now.plus(365, ChronoUnit.DAYS)
                : now.plus(30, ChronoUnit.DAYS);

        controlJdbc.sql("""
                UPDATE subscriptions
                SET status = 'CANCELED', updated_at = NOW()
                WHERE tenant_id = :tid AND status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'GRACE')
                """)
                .param("tid", payment.tenantId())
                .update();

        controlJdbc.sql("""
                INSERT INTO subscriptions
                (id, tenant_id, plan_id, billing_cycle, currency, gateway, status, current_period_start, current_period_end)
                VALUES (:id, :tid, :planId, :cycle, :currency, :gateway, 'ACTIVE', :start, :end)
                """)
                .param("id", UUID.randomUUID())
                .param("tid", payment.tenantId())
                .param("planId", payment.targetPlanId())
                .param("cycle", payment.billingCycle())
                .param("currency", currency)
                .param("gateway", payment.gateway().name())
                .param("start", Timestamp.from(now))
                .param("end", Timestamp.from(periodEnd))
                .update();

        controlJdbc.sql("""
                UPDATE tenants
                SET current_plan_id = :planId,
                    default_currency = :currency,
                    status = 'ACTIVE',
                    updated_at = NOW()
                WHERE id = :tid
                """)
                .param("planId", payment.targetPlanId())
                .param("currency", currency)
                .param("tid", payment.tenantId())
                .update();

        auditWriter.writePlatformEvent(gatewayLower, "WEBHOOK", payment.tenantId(), "SUBSCRIPTION_ACTIVATED",
                "payment", payment.id().toString(), null,
                Map.of(
                        "reference", payment.reference(),
                        "gateway", payment.gateway().name(),
                        "amountMinor", payment.amountMinor(),
                        "currency", currency,
                        "source", source
                ),
                source);

        if (payerEmail != null && !payerEmail.isBlank()) {
            String orgName = controlJdbc.sql("SELECT name FROM tenants WHERE id = :tid")
                    .param("tid", payment.tenantId()).query(String.class).single();
            String planName = controlJdbc.sql("SELECT name FROM plans WHERE id = :pid")
                    .param("pid", payment.targetPlanId()).query(String.class).single();
            outboxService.enqueueEmail(
                    payment.tenantId(),
                    payerEmail,
                    "SUBSCRIPTION_PAYMENT_SUCCEEDED",
                    "Proofly payment receipt",
                    Map.of(
                            "orgName", orgName,
                            "planName", planName,
                            "gateway", payment.gateway().name(),
                            "amountDisplay", currency + " " + String.format(Locale.US, "%.2f", payment.amountMinor() / 100.0),
                            "reference", payment.reference()
                    ),
                    "receipt:" + payment.reference()
            );
        }
    }

    private void handleFailedPayment(PaymentRow payment, String payerEmail) {
        controlJdbc.sql("UPDATE payments SET status = 'FAILED' WHERE id = :id AND status = 'INITIATED'")
                .param("id", payment.id())
                .update();

        Instant graceUntil = Instant.now().plus(billingProps.gracePeriodDays(), ChronoUnit.DAYS);
        controlJdbc.sql("""
                UPDATE subscriptions
                SET status = 'GRACE', grace_until = :graceUntil, updated_at = NOW()
                WHERE tenant_id = :tid AND status IN ('ACTIVE', 'PAST_DUE')
                """)
                .param("graceUntil", Timestamp.from(graceUntil))
                .param("tid", payment.tenantId())
                .update();

        if (payerEmail != null && !payerEmail.isBlank()) {
            String orgName = controlJdbc.sql("SELECT name FROM tenants WHERE id = :tid")
                    .param("tid", payment.tenantId()).query(String.class).single();
            outboxService.enqueueEmail(
                    payment.tenantId(),
                    payerEmail,
                    "SUBSCRIPTION_PAYMENT_FAILED",
                    "Proofly payment failed",
                    Map.of("orgName", orgName, "graceUntil", graceUntil.toString()),
                    "dunning:" + payment.id()
            );
        }
    }

    private PaymentRow findPaymentForTenant(UUID tenantId, String reference) {
        return controlJdbc.sql("""
                SELECT id, tenant_id, target_plan_id, billing_cycle, paystack_reference,
                       gateway, provider_session_id, amount_minor, currency, status
                FROM payments
                WHERE paystack_reference = :ref AND tenant_id = :tid
                """)
                .param("ref", reference)
                .param("tid", tenantId)
                .query((rs, rowNum) -> mapPaymentRow(rs))
                .optional()
                .orElseThrow(() -> new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                        "Payment reference not found"));
    }

    private long readTenantCreditBalance(UUID tenantId, String currency) {
        long perCurrency = ledgerService.readCachedBalance("tenant:" + tenantId + ":credit:" + currency);
        if (perCurrency != 0L) {
            return perCurrency;
        }
        return "NGN".equals(currency) ? ledgerService.readCachedBalance("tenant:" + tenantId + ":credit") : 0L;
    }

    private List<CurrencyBalanceView> readAllTenantCreditBalances(UUID tenantId) {
        return controlJdbc.sql("""
                SELECT a.currency, COALESCE(b.balance_minor, 0) AS balance_minor
                FROM ledger_accounts a
                JOIN account_balances b ON b.account_id = a.id
                WHERE a.owner_tenant_id = :tid
                ORDER BY a.currency ASC
                """)
                .param("tid", tenantId)
                .query((rs, rowNum) -> {
                    String currency = rs.getString("currency").trim();
                    GatewayCode gateway = gatewayRouter.gatewayCodeForCurrency(currency);
                    return new CurrencyBalanceView(currency, gateway, rs.getLong("balance_minor"));
                })
                .list();
    }

    static PaymentRow mapPaymentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("target_plan_id", UUID.class),
                rs.getString("billing_cycle"),
                rs.getString("paystack_reference"),
                GatewayCode.fromString(rs.getString("gateway")),
                rs.getString("provider_session_id"),
                rs.getLong("amount_minor"),
                rs.getString("currency").trim(),
                rs.getString("status")
        );
    }

    private void markEventStatus(String providerEventId, String status) {
        controlJdbc.sql("UPDATE payment_events SET status = :status, processed_at = NOW() WHERE provider_event_id = :eid")
                .param("status", status)
                .param("eid", providerEventId)
                .update();
    }
}
