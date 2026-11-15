package com.proofly.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proofly.app")
public record AppProperties(
        String frontendBaseUrl,
        String apiBaseUrl,
        String supportEmail
) {}
