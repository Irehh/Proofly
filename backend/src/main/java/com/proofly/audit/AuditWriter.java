package com.proofly.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofly.common.CorrelationIdFilter;
import com.proofly.common.HashChainUtil;
import com.proofly.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.UUID;

@Service
public class AuditWriter {

    private final JdbcClient controlJdbc;
    private final JdbcClient tenantJdbc;
    private final ObjectMapper objectMapper;

    public AuditWriter(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                       DataSource tenantRoutingDataSource,
                       ObjectMapper objectMapper) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.objectMapper = objectMapper;
    }

    public void writePlatformEvent(String actorId,
                                   String actorType,
                                   UUID tenantId,
                                   String action,
                                   String entityType,
                                   String entityId,
                                   Object beforeState,
                                   Object afterState,
                                   String ipAddress) {
        String prevHash = controlJdbc.sql("""
                SELECT row_hash FROM platform_audit_log
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .query(String.class)
                .optional()
                .orElse(HashChainUtil.GENESIS_HASH);

        UUID id = UUID.randomUUID();
        String requestId = CorrelationIdFilter.currentOrGenerate();
        String beforeJson = toJsonOrNull(beforeState);
        String afterJson = toJsonOrNull(afterState);
        String payload = String.join("|", id.toString(), actorId, String.valueOf(tenantId),
                action, entityType, entityId, String.valueOf(beforeJson), String.valueOf(afterJson), requestId);
        String rowHash = HashChainUtil.computeRowHash(prevHash, payload);

        controlJdbc.sql("""
                INSERT INTO platform_audit_log
                (id, actor_id, actor_type, tenant_id, action, entity_type, entity_id,
                 before_state, after_state, request_id, ip_address, prev_hash, row_hash)
                VALUES (:id, :actorId, :actorType, :tenantId, :action, :entityType, :entityId,
                        CAST(:beforeState AS JSONB), CAST(:afterState AS JSONB), :requestId, :ip, :prevHash, :rowHash)
                """)
                .param("id", id)
                .param("actorId", actorId)
                .param("actorType", actorType)
                .param("tenantId", tenantId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("beforeState", beforeJson)
                .param("afterState", afterJson)
                .param("requestId", requestId)
                .param("ip", ipAddress)
                .param("prevHash", prevHash)
                .param("rowHash", rowHash)
                .update();
    }

    public void writeTenantEvent(UUID actorMembershipId,
                                 String action,
                                 String entityType,
                                 String entityId,
                                 Object beforeState,
                                 Object afterState,
                                 String ipAddress,
                                 UUID deviceId) {
        TenantContext.requireCurrent();
        String prevHash = tenantJdbc.sql("""
                SELECT row_hash FROM tenant_audit_log
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .query(String.class)
                .optional()
                .orElse(HashChainUtil.GENESIS_HASH);

        UUID id = UUID.randomUUID();
        String requestId = CorrelationIdFilter.currentOrGenerate();
        String beforeJson = toJsonOrNull(beforeState);
        String afterJson = toJsonOrNull(afterState);
        String payload = String.join("|", id.toString(), String.valueOf(actorMembershipId),
                action, entityType, entityId, String.valueOf(beforeJson), String.valueOf(afterJson), requestId);
        String rowHash = HashChainUtil.computeRowHash(prevHash, payload);

        tenantJdbc.sql("""
                INSERT INTO tenant_audit_log
                (id, actor_membership_id, action, entity_type, entity_id,
                 before_state, after_state, request_id, ip_address, device_id, prev_hash, row_hash)
                VALUES (:id, :actorMembershipId, :action, :entityType, :entityId,
                        CAST(:beforeState AS JSONB), CAST(:afterState AS JSONB), :requestId, :ip, :deviceId, :prevHash, :rowHash)
                """)
                .param("id", id)
                .param("actorMembershipId", actorMembershipId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("beforeState", beforeJson)
                .param("afterState", afterJson)
                .param("requestId", requestId)
                .param("ip", ipAddress)
                .param("deviceId", deviceId)
                .param("prevHash", prevHash)
                .param("rowHash", rowHash)
                .update();
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit payload", e);
        }
    }
}
