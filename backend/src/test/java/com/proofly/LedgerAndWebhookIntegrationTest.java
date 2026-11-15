package com.proofly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofly.billing.BillingDunningAndReconciliationJob;
import com.proofly.billing.LedgerService;
import com.proofly.billing.SubscriptionAndBillingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LedgerAndWebhookIntegrationTest {

    private static final String TEST_PAYSTACK_SECRET = "sk_test_proofly_integration_secret";
    private static final String TEST_STRIPE_SECRET = "whsec_proofly_stripe_integration_secret";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("proofly_control")
            .withUsername("proofly_admin")
            .withPassword("proofly_test_pw");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("proofly.control-plane.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("proofly.control-plane.username", POSTGRES::getUsername);
        registry.add("proofly.control-plane.password", POSTGRES::getPassword);
        registry.add("proofly.tenancy.default-db-host", POSTGRES::getHost);
        registry.add("proofly.tenancy.default-db-port", POSTGRES::getFirstMappedPort);
        registry.add("proofly.tenancy.db-username", POSTGRES::getUsername);
        registry.add("proofly.tenancy.db-password", POSTGRES::getPassword);
        registry.add("proofly.billing.paystack.webhook-secret", () -> TEST_PAYSTACK_SECRET);
        registry.add("proofly.billing.stripe.webhook-secret", () -> TEST_STRIPE_SECRET);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    }

    @Autowired MockMvc mockMvc;
    @Autowired LedgerService ledgerService;
    @Autowired SubscriptionAndBillingService billingService;
    @Autowired BillingDunningAndReconciliationJob billingJobs;
    @Autowired @Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource;

    @Test
    void listPlansReturnsExactlyFourCheaperMultiCurrencyTiers() throws Exception {
        mockMvc.perform(get("/api/v1/billing/plans").param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].code").value("FREE"))
                .andExpect(jsonPath("$[1].code").value("STARTER"))
                .andExpect(jsonPath("$[1].currency").value("USD"))
                .andExpect(jsonPath("$[1].gateway").value("STRIPE"))
                .andExpect(jsonPath("$[1].monthlyPriceMinor").value(800))
                .andExpect(jsonPath("$[2].code").value("GROWTH"))
                .andExpect(jsonPath("$[3].code").value("ENTERPRISE"));
    }

    @Test
    void randomizedPropertyAndConcurrencyInvariantsHold() throws Exception {
        UUID clearing = ledgerService.getOrCreateAccount("test:clearing", null, "ASSET", "NGN", true);
        UUID credit = ledgerService.getOrCreateAccount("test:credit", null, "LIABILITY", "NGN", false);
        UUID revenue = ledgerService.getOrCreateAccount("test:revenue", null, "REVENUE", "NGN", false);

        Random rng = new Random(42L);
        long expectedCredit = 0L;

        for (int i = 0; i < 25; i++) {
            long deposit = (rng.nextInt(50) + 1) * 1000L;
            ledgerService.postBalancedEntry(new LedgerService.EntryRequest(
                    "prop-dep-" + i, "SETTLEMENT", "ref-" + i, "Deposit", Map.of(), null, "test",
                    List.of(
                            new LedgerService.LineSpec(clearing, LedgerService.Direction.DEBIT, deposit, "NGN"),
                            new LedgerService.LineSpec(credit, LedgerService.Direction.CREDIT, deposit, "NGN")
                    )
            ));
            expectedCredit += deposit;

            long spend = Math.min(expectedCredit, (rng.nextInt(25) + 1) * 500L);
            if (spend > 0) {
                ledgerService.postBalancedEntry(new LedgerService.EntryRequest(
                        "prop-chg-" + i, "CHARGE", "chg-" + i, "Charge", Map.of(), null, "test",
                        List.of(
                                new LedgerService.LineSpec(credit, LedgerService.Direction.DEBIT, spend, "NGN"),
                                new LedgerService.LineSpec(revenue, LedgerService.Direction.CREDIT, spend, "NGN")
                        )
                ));
                expectedCredit -= spend;
            }
        }

        assertThat(ledgerService.verifyAccountAgainstLedger(clearing)).isEqualTo(
                ledgerService.readCachedBalance("test:clearing"));
        assertThat(ledgerService.verifyAccountAgainstLedger(credit)).isEqualTo(expectedCredit);
        assertThat(ledgerService.verifyAccountAgainstLedger(revenue)).isEqualTo(
                ledgerService.readCachedBalance("test:revenue"));

        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<UUID>> jobs = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                final int idx = t;
                jobs.add(() -> ledgerService.postBalancedEntry(new LedgerService.EntryRequest(
                        "conc-" + idx, "SETTLEMENT", "cref-" + idx, "Parallel", Map.of(), null, "test",
                        List.of(
                                new LedgerService.LineSpec(clearing, LedgerService.Direction.DEBIT, 10000L, "NGN"),
                                new LedgerService.LineSpec(credit, LedgerService.Direction.CREDIT, 10000L, "NGN")
                        )
                )));
            }
            for (var fut : pool.invokeAll(jobs)) {
                fut.get();
            }
        }

        assertThat(ledgerService.verifyAccountAgainstLedger(credit)).isEqualTo(expectedCredit + 160_000L);
        billingJobs.reconcileAccountBalances();
        Integer checkpoints = JdbcClient.create(controlPlaneDataSource)
                .sql("SELECT COUNT(1) FROM balance_checkpoints WHERE account_id = :id")
                .param("id", credit)
                .query(Integer.class)
                .single();
        assertThat(checkpoints).isGreaterThanOrEqualTo(1);
    }

    @Test
    void paystackAndStripeWebhooksRouteByGatewayAndSettleIdempotently() throws Exception {
        String signupJson = mockMvc.perform(post("/api/v1/auth/signup-org")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgName": "Abuja Fleet",
                                  "slug": "abuja-fleet",
                                  "fullName": "Chidi Abuja",
                                  "email": "chidi@abujafleet.example",
                                  "password": "StrongPassword!234",
                                  "clientType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID tenantId = UUID.fromString(new ObjectMapper()
                .readTree(signupJson).get("user").get("tenantId").asText());

        JdbcClient jdbc = JdbcClient.create(controlPlaneDataSource);
        UUID starterPlanId = jdbc.sql("SELECT id FROM plans WHERE code = 'STARTER'").query(UUID.class).single();
        UUID growthPlanId = jdbc.sql("SELECT id FROM plans WHERE code = 'GROWTH'").query(UUID.class).single();

        // 1. NGN via PAYSTACK (Starter monthly = 900,000 kobo)
        String paystackRef = "prf_ps_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.sql("""
                INSERT INTO payments (id, tenant_id, target_plan_id, billing_cycle, paystack_reference, gateway,
                                      amount_minor, currency, status, idempotency_key)
                VALUES (:id, :tid, :pid, 'MONTHLY', :ref, 'PAYSTACK', 900000, 'NGN', 'INITIATED', :ikey)
                """)
                .param("id", UUID.randomUUID())
                .param("tid", tenantId)
                .param("pid", starterPlanId)
                .param("ref", paystackRef)
                .param("ikey", "idem-" + paystackRef)
                .update();

        String paystackPayload = """
                {
                  "event": "charge.success",
                  "data": {
                    "id": 99887766,
                    "reference": "%s",
                    "amount": 900000,
                    "currency": "NGN",
                    "customer": { "email": "chidi@abujafleet.example" }
                  }
                }
                """.formatted(paystackRef);

        mockMvc.perform(post("/api/v1/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-paystack-signature", "deadbeef")
                        .content(paystackPayload))
                .andExpect(status().isUnauthorized());

        String paystackSig = hmacHex("HmacSHA512", paystackPayload, TEST_PAYSTACK_SECRET);
        mockMvc.perform(post("/api/v1/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-paystack-signature", paystackSig)
                        .content(paystackPayload))
                .andExpect(status().isOk());

        // Idempotent replay
        mockMvc.perform(post("/api/v1/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-paystack-signature", paystackSig)
                        .content(paystackPayload))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT status FROM payments WHERE paystack_reference = :ref")
                .param("ref", paystackRef).query(String.class).single()).isEqualTo("SUCCEEDED");

        // 2. USD via STRIPE (Growth monthly = 2,400 cents)
        String stripeRef = "prf_st_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.sql("""
                INSERT INTO payments (id, tenant_id, target_plan_id, billing_cycle, paystack_reference, gateway,
                                      provider_session_id, amount_minor, currency, status, idempotency_key)
                VALUES (:id, :tid, :pid, 'MONTHLY', :ref, 'STRIPE', 'cs_test_123', 2400, 'NGN', 'INITIATED', :ikey)
                """.replace("'NGN'", "'USD'"))
                .param("id", UUID.randomUUID())
                .param("tid", tenantId)
                .param("pid", growthPlanId)
                .param("ref", stripeRef)
                .param("ikey", "idem-" + stripeRef)
                .update();

        String stripePayload = """
                {
                  "id": "evt_stripe_1001",
                  "type": "checkout.session.completed",
                  "data": {
                    "object": {
                      "id": "cs_test_123",
                      "client_reference_id": "%s",
                      "payment_status": "paid",
                      "amount_total": 2400,
                      "currency": "usd",
                      "customer_details": { "email": "chidi@abujafleet.example" },
                      "metadata": { "reference": "%s" }
                    }
                  }
                }
                """.formatted(stripeRef, stripeRef);

        long nowTs = Instant.now().getEpochSecond();
        String stripeSig = "t=" + nowTs + ",v1=" + hmacHex("HmacSHA256", nowTs + "." + stripePayload, TEST_STRIPE_SECRET);

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=" + nowTs + ",v1=bad")
                        .content(stripePayload))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", stripeSig)
                        .content(stripePayload))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT status FROM payments WHERE paystack_reference = :ref")
                .param("ref", stripeRef).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(ledgerService.readCachedBalance("platform:revenue:subscriptions:USD")).isEqualTo(2400L);
    }

    private static String hmacHex(String alg, String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(alg);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), alg));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
