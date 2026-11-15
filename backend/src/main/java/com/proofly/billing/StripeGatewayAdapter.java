package com.proofly.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class StripeGatewayAdapter implements PaymentGateway {

    private final BillingProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public StripeGatewayAdapter(BillingProperties props,
                                RestClient.Builder restClientBuilder,
                                ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(props.stripe().baseUrl())
                .build();
    }

    @Override
    public GatewayCode gatewayCode() {
        return GatewayCode.STRIPE;
    }

    @Override
    @Retry(name = "stripe")
    @CircuitBreaker(name = "stripe")
    public CheckoutInitResult initializeCheckout(CheckoutInitRequest req) {
        String sep = props.stripe().successUrl().contains("?") ? "&" : "?";
        String successUrl = props.stripe().successUrl()
                + sep + "reference=" + URLEncoder.encode(req.reference(), StandardCharsets.UTF_8)
                + "&gateway=stripe";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("customer_email", req.payerEmail());
        form.add("client_reference_id", req.reference());
        form.add("success_url", successUrl);
        form.add("cancel_url", props.stripe().cancelUrl());
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", req.currency().toLowerCase(Locale.ROOT));
        form.add("line_items[0][price_data][unit_amount]", String.valueOf(req.amountMinor()));
        form.add("line_items[0][price_data][product_data][name]",
                "Proofly " + req.planName() + " (" + req.billingCycle() + ")");
        form.add("metadata[tenantId]", req.tenantId().toString());
        form.add("metadata[planCode]", req.planCode());
        form.add("metadata[billingCycle]", req.billingCycle());
        form.add("metadata[reference]", req.reference());

        JsonNode root = restClient.post()
                .uri("/v1/checkout/sessions")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Authorization", "Bearer " + props.stripe().secretKey())
                .header("Idempotency-Key", req.reference())
                .body(form)
                .retrieve()
                .body(JsonNode.class);

        if (root == null || root.path("url").asText("").isBlank()) {
            throw new ProoflyException(HttpStatus.BAD_GATEWAY, ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Stripe checkout session initialization failed");
        }

        return new CheckoutInitResult(
                GatewayCode.STRIPE,
                root.path("url").asText(),
                root.path("id").asText(null),
                req.reference()
        );
    }

    @Override
    @Retry(name = "stripe")
    @CircuitBreaker(name = "stripe")
    public VerifyResult verifyTransaction(String reference, String providerSessionId) {
        if (providerSessionId == null || providerSessionId.isBlank()) {
            return new VerifyResult(GatewayCode.STRIPE, reference, "uninitiated_session",
                    NormalizedStatus.PENDING, -1L, "", "");
        }
        JsonNode root = restClient.get()
                .uri("/v1/checkout/sessions/{sessionId}", providerSessionId)
                .header("Authorization", "Bearer " + props.stripe().secretKey())
                .retrieve()
                .body(JsonNode.class);

        if (root == null) {
            throw new ProoflyException(HttpStatus.BAD_GATEWAY, ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Stripe checkout session verification failed");
        }

        String paymentStatus = root.path("payment_status").asText("");
        String sessionStatus = root.path("status").asText("");
        NormalizedStatus normalized;
        if ("paid".equalsIgnoreCase(paymentStatus)) {
            normalized = NormalizedStatus.SUCCEEDED;
        } else if ("expired".equalsIgnoreCase(sessionStatus)) {
            normalized = NormalizedStatus.ABANDONED;
        } else {
            normalized = NormalizedStatus.PENDING;
        }

        return new VerifyResult(
                GatewayCode.STRIPE,
                root.path("client_reference_id").asText(reference),
                paymentStatus + ":" + sessionStatus,
                normalized,
                root.path("amount_total").asLong(-1L),
                root.path("currency").asText("").toUpperCase(Locale.ROOT),
                root.path("customer_details").path("email").asText(root.path("customer_email").asText(""))
        );
    }

    /**
     * Verifies Stripe-Signature header ("t=<unix_ts>,v1=<hmac_sha256_hex>") with timestamp replay tolerance.
     */
    @Override
    public boolean isValidWebhookSignature(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String timestamp = null;
        java.util.List<String> v1Signatures = new java.util.ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) {
                timestamp = kv[1].trim();
            } else if ("v1".equals(kv[0])) {
                v1Signatures.add(kv[1].trim().toLowerCase(Locale.ROOT));
            }
        }
        if (timestamp == null || v1Signatures.isEmpty()) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            long tolerance = Math.max(30L, props.stripe().webhookToleranceSeconds());
            long ageSeconds = Math.abs(Instant.now().getEpochSecond() - ts);
            if (ageSeconds > tolerance) {
                return false;
            }
            byte[] signedPayload = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
            byte[] combined = new byte[signedPayload.length + rawBody.length];
            System.arraycopy(signedPayload, 0, combined, 0, signedPayload.length);
            System.arraycopy(rawBody, 0, combined, signedPayload.length, rawBody.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.stripe().webhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            String expectedHex = HexFormat.of().formatHex(mac.doFinal(combined));
            byte[] expectedBytes = expectedHex.getBytes(StandardCharsets.UTF_8);
            for (String candidate : v1Signatures) {
                if (MessageDigest.isEqual(expectedBytes, candidate.getBytes(StandardCharsets.UTF_8))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ParsedWebhookEvent parseWebhookEvent(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Malformed Stripe webhook JSON");
        }
        String eventType = root.path("type").asText("");
        String rawId = root.path("id").asText("");
        JsonNode obj = root.path("data").path("object");

        String reference = obj.path("metadata").path("reference").asText("");
        if (reference.isBlank()) {
            reference = obj.path("client_reference_id").asText("");
        }

        WebhookOutcome outcome = switch (eventType) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                    "paid".equalsIgnoreCase(obj.path("payment_status").asText("paid"))
                            ? WebhookOutcome.SUCCEEDED
                            : WebhookOutcome.IGNORED;
            case "checkout.session.async_payment_failed", "payment_intent.payment_failed" -> WebhookOutcome.FAILED;
            default -> WebhookOutcome.IGNORED;
        };

        long amountMinor = obj.hasNonNull("amount_total")
                ? obj.path("amount_total").asLong(-1L)
                : obj.path("amount").asLong(-1L);

        String email = obj.path("customer_details").path("email").asText("");
        if (email.isBlank()) {
            email = obj.path("customer_email").asText("");
        }
        if (email.isBlank()) {
            email = obj.path("receipt_email").asText("");
        }

        return new ParsedWebhookEvent(
                GatewayCode.STRIPE,
                "stripe:" + (!rawId.isBlank() ? rawId : eventType + ":" + reference),
                eventType,
                reference,
                obj.path("id").asText(null),
                outcome,
                amountMinor,
                obj.path("currency").asText("").toUpperCase(Locale.ROOT),
                email,
                root.toString()
        );
    }
}
