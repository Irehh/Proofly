package com.proofly.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofly.common.CorrelationIdFilter;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationOutboxService {

    public record OutboxView(
            UUID id,
            String channel,
            String recipient,
            String templateCode,
            String subject,
            String status,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            String lastError,
            Instant sentAt,
            Instant createdAt
    ) {}

    private final JdbcClient controlJdbc;
    private final NotificationProperties props;
    private final ObjectMapper objectMapper;

    public NotificationOutboxService(@Qualifier("controlPlaneDataSource") DataSource controlPlaneDataSource,
                                     NotificationProperties props,
                                     ObjectMapper objectMapper) {
        this.controlJdbc = JdbcClient.create(controlPlaneDataSource);
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public UUID enqueueEmail(UUID tenantId,
                             String toEmail,
                             String templateCode,
                             String subject,
                             Map<String, Object> payload,
                             String idempotencyKey) {
        return enqueue(tenantId, "EMAIL", toEmail, templateCode, subject, payload, idempotencyKey);
    }

    public UUID enqueueSms(UUID tenantId,
                           String phoneE164,
                           String templateCode,
                           Map<String, Object> payload,
                           String idempotencyKey) {
        return enqueue(tenantId, "SMS", phoneE164, templateCode, null, payload, idempotencyKey);
    }

    public UUID enqueue(UUID tenantId,
                        String channel,
                        String recipient,
                        String templateCode,
                        String subject,
                        Map<String, Object> payload,
                        String idempotencyKey) {
        UUID id = UUID.randomUUID();
        String payloadJson = toJson(payload != null ? payload : Map.of());
        String correlationId = CorrelationIdFilter.currentOrGenerate();

        controlJdbc.sql("""
                INSERT INTO notification_outbox
                (id, tenant_id, channel, recipient, template_code, subject, payload_jsonb,
                 idempotency_key, status, attempt_count, max_attempts, next_attempt_at, correlation_id)
                VALUES (:id, :tenantId, :channel, :recipient, :templateCode, :subject, CAST(:payload AS JSONB),
                        :idempotencyKey, 'PENDING', 0, :maxAttempts, NOW(), :corrId)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("channel", channel)
                .param("recipient", recipient)
                .param("templateCode", templateCode)
                .param("subject", subject)
                .param("payload", payloadJson)
                .param("idempotencyKey", idempotencyKey)
                .param("maxAttempts", props.maxAttempts())
                .param("corrId", correlationId)
                .update();

        return controlJdbc.sql("SELECT id FROM notification_outbox WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(UUID.class)
                .single();
    }

    public List<OutboxView> listForTenant(UUID tenantId, int limit) {
        return controlJdbc.sql("""
                SELECT id, channel, recipient, template_code, subject, status,
                       attempt_count, max_attempts, next_attempt_at, last_error, sent_at, created_at
                FROM notification_outbox
                WHERE tenant_id = :tenantId
                ORDER BY created_at DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("limit", Math.min(100, Math.max(1, limit)))
                .query((rs, rowNum) -> new OutboxView(
                        rs.getObject("id", UUID.class),
                        rs.getString("channel"),
                        maskRecipient(rs.getString("recipient")),
                        rs.getString("template_code"),
                        rs.getString("subject"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts"),
                        rs.getTimestamp("next_attempt_at").toInstant(),
                        rs.getString("last_error"),
                        rs.getTimestamp("sent_at") != null ? rs.getTimestamp("sent_at").toInstant() : null,
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    public void retryForTenant(UUID tenantId, UUID notificationId) {
        int updated = controlJdbc.sql("""
                UPDATE notification_outbox
                SET status = 'PENDING',
                    attempt_count = 0,
                    next_attempt_at = NOW(),
                    last_error = NULL
                WHERE id = :id AND tenant_id = :tenantId AND status IN ('FAILED', 'DEAD_LETTER')
                """)
                .param("id", notificationId)
                .param("tenantId", tenantId)
                .update();

        if (updated == 0) {
            throw new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                    "Retryable notification not found for tenant");
        }
    }

    public Map<String, Object> parsePayload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize notification payload", e);
        }
    }

    private static String maskRecipient(String raw) {
        if (raw == null || raw.length() < 5) return "***";
        int at = raw.indexOf('@');
        if (at > 1) {
            return raw.charAt(0) + "***" + raw.substring(at);
        }
        return "***" + raw.substring(raw.length() - 4);
    }
}
