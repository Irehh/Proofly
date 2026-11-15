package com.proofly.tenancy;

import com.proofly.common.CorrelationIdFilter;
import com.proofly.common.TenantId;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * Ensures pooled async/virtual threads never inherit stale TenantContext from a previous task.
 * Tasks requiring a tenant must bind TenantContext explicitly via TenantContext.runWithTenant(...).
 */
@Component
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        TenantId capturedTenant = TenantContext.current().orElse(null);

        return () -> {
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            if (capturedTenant != null) {
                TenantContext.set(capturedTenant);
            } else {
                TenantContext.clear();
            }
            try {
                runnable.run();
            } finally {
                TenantContext.clear();
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        };
    }
}
