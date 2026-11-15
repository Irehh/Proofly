package com.proofly.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "proofly.billing")
public record BillingProperties(
        String defaultCurrency,
        PaystackProps paystack,
        StripeProps stripe,
        int gracePeriodDays,
        long majorOpReverifyThresholdMinor,
        int pendingSweepMinMinutes,
        int pendingSweepMaxHours,
        int pendingSweepBatchSize
) {
    public record PaystackProps(
            String baseUrl,
            String secretKey,
            String publicKey,
            String webhookSecret,
            String callbackUrl,
            List<String> currencies
    ) {}

    public record StripeProps(
            String baseUrl,
            String secretKey,
            String publicKey,
            String webhookSecret,
            long webhookToleranceSeconds,
            String successUrl,
            String cancelUrl,
            List<String> currencies
    ) {}
}
