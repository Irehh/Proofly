package com.proofly.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proofly.notification")
public record NotificationProperties(
        String fromEmail,
        String fromName,
        String smsProvider,
        String smsApiUrl,
        String smsApiKey,
        String smsSenderId,
        int maxAttempts,
        long baseRetrySeconds,
        int batchSize,
        long processingTimeoutSeconds
) {}
