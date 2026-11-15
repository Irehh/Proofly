package com.proofly.audit;

import com.proofly.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Audit Log", description = "Immutable SHA-256 hash-chained who/when/why tenant audit trail")
public class AuditController {

    public record TenantAuditEntryView(
            UUID id,
            UUID actorMembershipId,
            String actorName,
            String action,
            String entityType,
            String entityId,
            String beforeStateJson,
            String afterStateJson,
            String requestId,
            String rowHash,
            Instant createdAt
    ) {}

    private final JdbcClient tenantJdbc;

    public AuditController(DataSource tenantRoutingDataSource) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
    }

    @GetMapping("/api/v1/audit/tenant-logs")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "List tenant SHA-256 chained audit log entries")
    public List<TenantAuditEntryView> listTenantAuditLogs(
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        TenantContext.requireCurrent();
        int clamped = Math.min(200, Math.max(1, limit));
        return tenantJdbc.sql("""
                SELECT a.id, a.actor_membership_id, COALESCE(u.full_name, 'System') AS actor_name,
                       a.action, a.entity_type, a.entity_id,
                       a.before_state::text AS before_json,
                       a.after_state::text AS after_json,
                       a.request_id, a.row_hash, a.created_at
                FROM tenant_audit_log a
                LEFT JOIN memberships m ON m.id = a.actor_membership_id
                LEFT JOIN users u ON u.id = m.user_id
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT :limit
                """)
                .param("limit", clamped)
                .query((rs, rowNum) -> new TenantAuditEntryView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_membership_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("before_json"),
                        rs.getString("after_json"),
                        rs.getString("request_id"),
                        rs.getString("row_hash"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }
}
