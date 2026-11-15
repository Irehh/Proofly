package com.proofly.notification;

import com.proofly.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Tenant notification outbox monitoring and dead-letter replay")
public class NotificationController {

    private final NotificationOutboxService outboxService;

    public NotificationController(NotificationOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @GetMapping("/outbox")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "List recent notification outbox deliveries for current tenant (masked recipients)")
    public List<NotificationOutboxService.OutboxView> listOutbox(
            @RequestParam(defaultValue = "25") int limit
    ) {
        var tenantId = TenantContext.requireCurrent();
        return outboxService.listForTenant(tenantId.value(), limit);
    }

    @PostMapping("/outbox/{notificationId}/retry")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Re-queue a failed or dead-letter notification for current tenant")
    public ResponseEntity<Void> retryNotification(@PathVariable UUID notificationId) {
        var tenantId = TenantContext.requireCurrent();
        outboxService.retryForTenant(tenantId.value(), notificationId);
        return ResponseEntity.noContent().build();
    }
}
