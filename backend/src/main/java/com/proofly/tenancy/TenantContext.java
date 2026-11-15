package com.proofly.tenancy;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.function.Supplier;

public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();
    private static final String MDC_TENANT_KEY = "tenantId";

    private TenantContext() {}

    public static void set(TenantId tenantId) {
        if (tenantId == null) {
            clear();
            return;
        }
        CURRENT.set(tenantId);
        MDC.put(MDC_TENANT_KEY, tenantId.toString());
    }

    public static Optional<TenantId> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantId requireCurrent() {
        TenantId tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new ProoflyException(
                    HttpStatus.UNAUTHORIZED,
                    ErrorCode.TENANT_CONTEXT_MISSING,
                    "Tenant context is required for tenant-scoped data access"
            );
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_TENANT_KEY);
    }

    public static <T> T runWithTenant(TenantId tenantId, Supplier<T> action) {
        TenantId previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                set(previous);
            } else {
                clear();
            }
        }
    }

    public static void runWithTenant(TenantId tenantId, Runnable action) {
        runWithTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
