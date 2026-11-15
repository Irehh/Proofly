package com.proofly.identity;

import com.proofly.audit.AuditWriter;
import com.proofly.common.ErrorCode;
import com.proofly.common.HashChainUtil;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.proofly.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Implements rotating refresh tokens with family-wide reuse detection.
 * Token wire format: <tenantId>.<familyId>.<randomSecret>
 */
@Service
public class RefreshTokenService {

    public record IssuedRefresh(UUID familyId, String rawToken, Instant expiresAt) {}
    public record RotatedSession(UUID tenantId, UUID userId, UUID membershipId, String role, UUID deviceId,
                                 UUID familyId, String newRawRefreshToken, Instant expiresAt) {}

    private record FamilyRow(UUID id, UUID userId, UUID deviceId, UUID familyId,
                             String currentTokenHash, String parentTokenHash,
                             Instant reuseDetectedAt, Instant expiresAt, Instant revokedAt) {}

    private final JdbcClient tenantJdbc;
    private final SecurityProperties props;
    private final AuditWriter auditWriter;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(DataSource tenantRoutingDataSource,
                               SecurityProperties props,
                               AuditWriter auditWriter) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.props = props;
        this.auditWriter = auditWriter;
    }

    public IssuedRefresh createFamily(UUID userId, UUID deviceId) {
        TenantContext.requireCurrent();
        UUID familyId = UUID.randomUUID();
        String secret = generateSecret();
        String tokenHash = HashChainUtil.sha256Hex(secret);
        Instant expiresAt = Instant.now().plusSeconds(props.jwt().refreshTokenTtlSeconds());

        tenantJdbc.sql("""
                INSERT INTO refresh_token_families
                (id, user_id, device_id, family_id, current_token_hash, parent_token_hash, expires_at)
                VALUES (:id, :userId, :deviceId, :familyId, :currentHash, NULL, :expiresAt)
                """)
                .param("id", UUID.randomUUID())
                .param("userId", userId)
                .param("deviceId", deviceId)
                .param("familyId", familyId)
                .param("currentHash", tokenHash)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();

        String wireToken = formatWireToken(TenantContext.requireCurrent().value(), familyId, secret);
        return new IssuedRefresh(familyId, wireToken, expiresAt);
    }

    public RotatedSession rotate(String rawRefreshToken, String ipAddress) {
        ParsedWire parsed = parseWireToken(rawRefreshToken);
        return TenantContext.runWithTenant(new TenantId(parsed.tenantId()), () -> rotateInTenant(parsed, ipAddress));
    }

    @Transactional
    protected RotatedSession rotateInTenant(ParsedWire parsed, String ipAddress) {
        FamilyRow family = tenantJdbc.sql("""
                SELECT id, user_id, device_id, family_id, current_token_hash, parent_token_hash,
                       reuse_detected_at, expires_at, revoked_at
                FROM refresh_token_families
                WHERE family_id = :familyId
                FOR UPDATE
                """)
                .param("familyId", parsed.familyId())
                .query((rs, rowNum) -> new FamilyRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("device_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        rs.getString("current_token_hash"),
                        rs.getString("parent_token_hash"),
                        rs.getTimestamp("reuse_detected_at") != null ? rs.getTimestamp("reuse_detected_at").toInstant() : null,
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null
                ))
                .optional()
                .orElseThrow(() -> new ProoflyException(
                        HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Invalid refresh token"));

        if (family.revokedAt() != null || family.reuseDetectedAt() != null) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_REVOKED,
                    "Refresh token family has been revoked");
        }
        if (family.expiresAt().isBefore(Instant.now())) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Refresh token expired");
        }

        String presentedHash = HashChainUtil.sha256Hex(parsed.secret());

        // Replay / Reuse detection: any presentation of an older token in the chain revokes the entire family!
        if (!family.currentTokenHash().equals(presentedHash)) {
            tenantJdbc.sql("""
                    UPDATE refresh_token_families
                    SET reuse_detected_at = NOW(), revoked_at = NOW()
                    WHERE family_id = :familyId
                    """)
                    .param("familyId", parsed.familyId())
                    .update();
            auditWriter.writeTenantEvent(null, "REFRESH_TOKEN_REUSE_DETECTED", "refresh_token_family",
                    parsed.familyId().toString(), null,
                    java.util.Map.of("userId", family.userId().toString(), "deviceId", family.deviceId().toString()),
                    ipAddress, family.deviceId());
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.REFRESH_TOKEN_REUSED,
                    "Refresh token reuse detected; session family revoked");
        }

        // Verify device is still active
        Boolean deviceRevoked = tenantJdbc.sql("SELECT revoked_at IS NOT NULL FROM devices WHERE id = :id")
                .param("id", family.deviceId())
                .query(Boolean.class)
                .single();
        if (Boolean.TRUE.equals(deviceRevoked)) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_REVOKED, "Device has been revoked");
        }

        var memberInfo = tenantJdbc.sql("""
                SELECT m.id AS membership_id, m.role AS role, u.status AS user_status, m.status AS member_status
                FROM memberships m
                JOIN users u ON u.id = m.user_id
                WHERE u.id = :userId
                """)
                .param("userId", family.userId())
                .query((rs, rowNum) -> new Object[]{
                        rs.getObject("membership_id", UUID.class),
                        rs.getString("role"),
                        rs.getString("user_status"),
                        rs.getString("member_status")
                })
                .single();

        if (!"ACTIVE".equals(memberInfo[2]) || !"ACTIVE".equals(memberInfo[3])) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_REVOKED, "Account is not active");
        }

        String nextSecret = generateSecret();
        String nextHash = HashChainUtil.sha256Hex(nextSecret);
        Instant nextExpiry = Instant.now().plusSeconds(props.jwt().refreshTokenTtlSeconds());

        tenantJdbc.sql("""
                UPDATE refresh_token_families
                SET parent_token_hash = current_token_hash,
                    current_token_hash = :nextHash,
                    expires_at = :nextExpiry
                WHERE family_id = :familyId
                """)
                .param("nextHash", nextHash)
                .param("nextExpiry", java.sql.Timestamp.from(nextExpiry))
                .param("familyId", parsed.familyId())
                .update();

        tenantJdbc.sql("UPDATE devices SET last_seen_at = NOW() WHERE id = :id")
                .param("id", family.deviceId())
                .update();

        String nextWire = formatWireToken(parsed.tenantId(), parsed.familyId(), nextSecret);
        return new RotatedSession(
                parsed.tenantId(),
                family.userId(),
                (UUID) memberInfo[0],
                (String) memberInfo[1],
                family.deviceId(),
                parsed.familyId(),
                nextWire,
                nextExpiry
        );
    }

    public void revokeFamilyByToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        ParsedWire parsed = parseWireToken(rawRefreshToken);
        TenantContext.runWithTenant(new TenantId(parsed.tenantId()), () ->
                tenantJdbc.sql("UPDATE refresh_token_families SET revoked_at = NOW() WHERE family_id = :fid")
                        .param("fid", parsed.familyId())
                        .update()
        );
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String formatWireToken(UUID tenantId, UUID familyId, String secret) {
        return tenantId + "." + familyId + "." + secret;
    }

    private record ParsedWire(UUID tenantId, UUID familyId, String secret) {}

    private static ParsedWire parseWireToken(String raw) {
        if (raw == null) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Missing refresh token");
        }
        String[] parts = raw.split("\\.", 3);
        if (parts.length != 3) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Malformed refresh token");
        }
        try {
            return new ParsedWire(UUID.fromString(parts[0]), UUID.fromString(parts[1]), parts[2]);
        } catch (IllegalArgumentException e) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Malformed refresh token");
        }
    }
}
