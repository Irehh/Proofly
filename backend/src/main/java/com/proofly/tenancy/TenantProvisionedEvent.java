package com.proofly.tenancy;

import java.util.UUID;

public record TenantProvisionedEvent(UUID tenantId, String slug, String schemaVersion) {}
