package com.proofly.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "proofly.tenancy")
public record TenancyProperties(
        String defaultDbHost,
        int defaultDbPort,
        String dbUsername,
        String dbPassword,
        boolean usePgbouncer,
        int maxCachedPools,
        int perTenantMaxPoolSize,
        int perTenantMinIdle,
        int globalConnectionCap,
        int migrationConcurrency,
        int migrationFailureThreshold,
        String expectedTenantSchemaVersion
) {}
