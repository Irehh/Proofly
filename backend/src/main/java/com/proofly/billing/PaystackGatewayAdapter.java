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
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class PaystackGatewayAdapter implements PaymentGateway {

    private final BillingProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PaystackGatewayAdapter(BillingProperties props,
                                  RestClient.Builder restClientBuilder,
                                  ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder
                .baseUrl(props.paystack().baseUrl())
                .build();
    }

    @Override
    public GatewayCode gatewayCode() {
        return GatewayCode.PAYSTACK;
    }

    @Override
    @Retry(name = "paystack")
    @CircuitBreaker(name = "paystack")
    public CheckoutInitResult initializeCheckout(CheckoutInitRequest req) {
        JsonNode root = restClient.post()
                .uri("/transaction/initialize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + props.paystack().secretKey())
                .body(Map.of(
                        "email", req.payerEmail(),
                        "amount", String.valueOf(req.amountMinor()),
                        "currency", req.currency(),
                        "reference", req.reference(),
                        "callback_url", props.paystack().callbackUrl(),
                        "metadata", Map.of(
                                "tenantId", req.tenantId().toString(),
                                "planCode", req.planCode(),
                                "billingCycle", req.billingCycle(),
                                "gateway", GatewayCode.PAYSTACK.name()
                        )
                ))
                .retrieve()
                .body(JsonNode.class);

        if (root == null || !root.path("status").asBoolean(false)) {
            throw new ProoflyException(HttpStatus.BAD_GATEWAY, ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Paystack transaction initialization failed");
        }
        JsonNode data = root.path("data");
        String accessCode = data.path("access_code").asText(null);
        return new CheckoutInitResult(
                GatewayCode.PAYSTACK,
                data.path("authorization_url").asText(),
                accessCode,
                data.path("reference").asText(req.reference())
        );
    }

    @Override
    @Retry(name = "paystack")
    @CircuitBreaker(name = "paystack")
    public VerifyResult verifyTransaction(String reference, String providerSessionId) {
        JsonNode root = restClient.get()
                .uri("/transaction/verify/{reference}", reference)
                .header("Authorization", "Bearer " + props.paystack().secretKey())
                .retrieve()
                .body(JsonNode.class);

        if (root == null || !root.path("status").asBoolean(false)) {
            throw new ProoflyException(HttpStatus.BAD_GATEWAY, ErrorCode.PAYMENT_PROVIDER_ERROR,
                    "Paystack transaction verification failed");
        }
        JsonNode data = root.path("data");
        String rawStatus = data.path("status").asText("unknown");
        NormalizedStatus normalized = switch (rawStatus.toLowerCase(Locale.ROOT)) {
            case "success" -> NormalizedStatus.SUCCEEDED;
            case "failed" -> NormalizedStatus.FAILED;
            case "abandoned", "reversed" -> NormalizedStatus.ABANDONED;
            default -> NormalizedStatus.PENDING;
        };
        return new VerifyResult(
                GatewayCode.PAYSTACK,
                data.path("reference").asText(reference),
                rawStatus,
                normalized,
                data.path("amount").asLong(-1L),
                data.path("currency").asText("").toUpperCase(Locale.ROOT),
                data.path("customer").path("email").asText("")
        );
    }

    @Override
    public boolean isValidWebhookSignature(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(
                    props.paystack().webhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA512"
            ));
            byte[] computed = mac.doFinal(rawBody);
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
            );
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
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Malformed Paystack webhook JSON");
        }
        String eventType = root.path("event").asText("");
        JsonNode data = root.path("data");
        String reference = data.path("reference").asText("");
        String providerEventId = "paystack:" + eventType + ":" + data.path("id").asText(reference);
        WebhookOutcome outcome = switch (eventType) {
            case "charge.success" -> WebhookOutcome.SUCCEEDED;
            case "charge.failed", "invoice.payment_failed" -> WebhookOutcome.FAILED;
            default -> WebhookOutcome.IGNORED;
        };
        return new ParsedWebhookEvent(
                GatewayCode.PAYSTACK,
                providerEventId,
                eventType,
                reference,
                data.path("id").asText(null),
                outcome,
                data.path("amount").asLong(-1L),
                data.path("currency").asText("").toUpperCase(Locale.ROOT),
                data.path("customer").path("email").asText(""),
                root.toString()
        );
    }
}
