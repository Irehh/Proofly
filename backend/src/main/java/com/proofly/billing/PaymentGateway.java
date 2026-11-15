package com.proofly.billing;

import java.util.UUID;

public interface PaymentGateway {

    enum NormalizedStatus {
        PENDING,
        SUCCEEDED,
        FAILED,
        ABANDONED
    }

    enum WebhookOutcome {
        IGNORED,
        SUCCEEDED,
        FAILED
    }

    record CheckoutInitRequest(
            UUID tenantId,
            String payerEmail,
            String planCode,
            String planName,
            String billingCycle,
            long amountMinor,
            String currency,
            String reference
    ) {}

    record CheckoutInitResult(
            GatewayCode gateway,
            String authorizationUrl,
            String providerSessionId,
            String reference
    ) {}

    record VerifyResult(
            GatewayCode gateway,
            String reference,
            String rawProviderStatus,
            NormalizedStatus normalizedStatus,
            long amountMinor,
            String currency,
            String payerEmail
    ) {}

    record ParsedWebhookEvent(
            GatewayCode gateway,
            String providerEventId,
            String eventType,
            String reference,
            String providerSessionId,
            WebhookOutcome outcome,
            long amountMinor,
            String currency,
            String payerEmail,
            String rawPayload
    ) {}

    GatewayCode gatewayCode();

    CheckoutInitResult initializeCheckout(CheckoutInitRequest request);

    VerifyResult verifyTransaction(String reference, String providerSessionId);

    boolean isValidWebhookSignature(byte[] rawBody, String signatureHeader);

    ParsedWebhookEvent parseWebhookEvent(byte[] rawBody);
}
