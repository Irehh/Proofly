package com.proofly.identity;

import com.proofly.audit.AuditWriter;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeviceService {

    public record DeviceView(UUID id, String deviceName, String platform, Instant lastSeenAt, Instant revokedAt, Instant createdAt) {}

    private final JdbcClient tenantJdbc;
    private final AuditWriter auditWriter;

    public DeviceService(DataSource tenantRoutingDataSource, AuditWriter auditWriter) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.auditWriter = auditWriter;
    }

    public UUID registerOrTouchDevice(UUID userId, String fingerprint, String deviceName, String platform) {
        TenantContext.requireCurrent();
        String safeFingerprint = (fingerprint != null && !fingerprint.isBlank()) ? fingerprint.trim() : UUID.randomUUID().toString();
        String safeName = (deviceName != null && !deviceName.isBlank()) ? deviceName.trim() : "Proofly Client";
        String safePlatform = (platform != null && List.of("WEB_PWA", "IOS", "ANDROID", "API").contains(platform))
                ? platform : "WEB_PWA";

        return tenantJdbc.sql("""
                INSERT INTO devices (id, user_id, device_fingerprint, device_name, platform, last_seen_at, revoked_at)
                VALUES (:id, :userId, :fp, :name, :platform, NOW(), NULL)
                ON CONFLICT (user_id, device_fingerprint)
                DO UPDATE SET device_name = EXCLUDED.device_name,
                              platform = EXCLUDED.platform,
                              last_seen_at = NOW(),
                              revoked_at = NULL
                RETURNING id
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("fp", safeFingerprint)
                .param("name", safeName)
                .param("platform", safePlatform)
                .query(UUID.class)
                .single();
    }

    public List<DeviceView> listUserDevices(UUID userId) {
        TenantContext.requireCurrent();
        return tenantJdbc.sql("""
                SELECT id, device_name, platform, last_seen_at, revoked_at, created_at
                FROM devices
                WHERE user_id = :userId
                ORDER BY last_seen_at DESC
                """)
                .param("userId", userId)
                .query((rs, rowNum) -> new DeviceView(
                        rs.getObject("id", UUID.class),
                        rs.getString("device_name"),
                        rs.getString("platform"),
                        rs.getTimestamp("last_seen_at").toInstant(),
                        rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null,
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    @Transactional
    public void revokeDevice(UUID userId, UUID membershipId, UUID deviceId, String ipAddress) {
        TenantContext.requireCurrent();
        int updated = tenantJdbc.sql("""
                UPDATE devices SET revoked_at = NOW()
                WHERE id = :deviceId AND user_id = :userId AND revoked_at IS NULL
                """)
                .param("deviceId", deviceId)
                .param("userId", userId)
                .update();

        if (updated == 0) {
            throw new ProoflyException(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "Active device not found");
        }

        tenantJdbc.sql("UPDATE refresh_token_families SET revoked_at = NOW() WHERE device_id = :deviceId")
                .param("deviceId", deviceId)
                .update();

        auditWriter.writeTenantEvent(membershipId, "DEVICE_REVOKED", "device", deviceId.toString(),
                null, Map.of("revoked", true), ipAddress, deviceId);
    }
}
