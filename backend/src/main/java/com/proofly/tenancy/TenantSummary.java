package com.proofly.tenancy;

import com.proofly.common.TenantId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record TenantSummary(
        UUID id,
        String slug,
        String name,
        String status,
        String dbHost,
        int dbPort,
        String dbName,
        String schemaVersion,
        UUID currentPlanId,
        String defaultCurrency,
        Instant createdAt
) {
    public TenantId tenantId() {
        return new TenantId(id);
    }

    public record DirectoryMatch(UUID tenantId, UUID tenantUserId, String status) {}

    public record PlanQuota(String planCode, String planName, int maxMembers, int maxSites) {}

    public interface DirectoryLookup {
        Optional<DirectoryMatch> findByEmail(String email);
        Optional<DirectoryMatch> findByPhone(String phoneE164);
    }
}
