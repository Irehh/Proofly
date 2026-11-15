package com.proofly.identity;

import com.proofly.audit.AuditWriter;
import com.proofly.common.AppProperties;
import com.proofly.common.ErrorCode;
import com.proofly.common.HashChainUtil;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.proofly.notification.NotificationOutboxService;
import com.proofly.tenancy.TenantContext;
import com.proofly.tenancy.TenantDirectoryService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountRecoveryService {

    private record TokenRow(UUID id, UUID userId, Instant expiresAt, Instant usedAt) {}

    private final JdbcClient tenantJdbc;
    private final TenantDirectoryService directoryService;
    private final Argon2PasswordService passwordService;
    private final NotificationOutboxService outboxService;
    private final TokenDenylistAndRateLimitService rateLimitService;
    private final AuditWriter auditWriter;
    private final SecurityProperties securityProps;
    private final AppProperties appProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountRecoveryService(DataSource tenantRoutingDataSource,
                                  TenantDirectoryService directoryService,
                                  Argon2PasswordService passwordService,
                                  NotificationOutboxService outboxService,
                                  TokenDenylistAndRateLimitService rateLimitService,
                                  AuditWriter auditWriter,
                                  SecurityProperties securityProps,
                                  AppProperties appProperties) {
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.directoryService = directoryService;
        this.passwordService = passwordService;
        this.outboxService = outboxService;
        this.rateLimitService = rateLimitService;
        this.auditWriter = auditWriter;
        this.securityProps = securityProps;
        this.appProperties = appProperties;
    }

    public void enqueueEmailVerification(UUID tenantId, UUID userId, String email, String fullName, String orgName) {
        String rawToken = generateToken();
        String tokenHash = HashChainUtil.sha256Hex(rawToken);
        Instant expiresAt = Instant.now().plus(securityProps.verifyTokenTtlHours(), ChronoUnit.HOURS);

        TenantContext.runWithTenant(new TenantId(tenantId), () -> {
            tenantJdbc.sql("""
                    UPDATE verification_tokens SET used_at = NOW()
                    WHERE user_id = :uid AND token_type = 'EMAIL_VERIFY' AND used_at IS NULL
                    """)
                    .param("uid", userId)
                    .update();

            tenantJdbc.sql("""
                    INSERT INTO verification_tokens (id, user_id, token_type, token_hash, expires_at)
                    VALUES (:id, :uid, 'EMAIL_VERIFY', :hash, :exp)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("uid", userId)
                    .param("hash", tokenHash)
                    .param("exp", Timestamp.from(expiresAt))
                    .update();
        });

        String actionUrl = appProperties.frontendBaseUrl() + "/verify-email?tid="
                + urlEncode(tenantId.toString()) + "&token=" + urlEncode(rawToken);

        outboxService.enqueueEmail(
                tenantId,
                email,
                "WELCOME_VERIFY_EMAIL",
                "Verify your Proofly email",
                Map.of(
                        "fullName", fullName,
                        "orgName", orgName,
                        "actionUrl", actionUrl
                ),
                "email-verify:" + tokenHash
        );
    }

    public void resendEmailVerification(UUID tenantId, UUID userId) {
        record UserInfo(String email, String fullName, boolean emailVerified, String orgName) {}
        UserInfo info = TenantContext.runWithTenant(new TenantId(tenantId), () -> {
            String orgName = tenantJdbc.sql("SELECT org_name FROM organization_settings LIMIT 1")
                    .query(String.class).optional().orElse("your workspace");
            return tenantJdbc.sql("""
                    SELECT email, full_name, email_verified
                    FROM users
                    WHERE id = :uid
                    """)
                    .param("uid", userId)
                    .query((rs, rowNum) -> new UserInfo(
                            rs.getString("email"),
                            rs.getString("full_name"),
                            rs.getBoolean("email_verified"),
                            orgName
                    ))
                    .single();
        });
        if (info.email() == null || info.email().isBlank()) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "No email address is registered on this account");
        }
        if (info.emailVerified()) {
            return;
        }
        enqueueEmailVerification(tenantId, userId, info.email(), info.fullName(), info.orgName());
    }

    public void confirmEmailVerification(UUID tenantId, String rawToken, String ipAddress) {
        String tokenHash = HashChainUtil.sha256Hex(rawToken);
        TenantContext.runWithTenant(new TenantId(tenantId), () -> confirmEmailInTenant(tokenHash, ipAddress));
    }

    @Transactional
    protected void confirmEmailInTenant(String tokenHash, String ipAddress) {
        TokenRow row = findValidToken(tokenHash, "EMAIL_VERIFY");

        tenantJdbc.sql("UPDATE verification_tokens SET used_at = NOW() WHERE id = :id")
                .param("id", row.id())
                .update();

        tenantJdbc.sql("UPDATE users SET email_verified = TRUE, email_verified_at = NOW() WHERE id = :uid")
                .param("uid", row.userId())
                .update();

        auditWriter.writeTenantEvent(null, "EMAIL_VERIFIED", "user", row.userId().toString(),
                Map.of("emailVerified", false), Map.of("emailVerified", true), ipAddress, null);
    }

    public void requestPasswordReset(String email, String ipAddress) {
        rateLimitService.checkAuthRateLimit("pwreset:" + ipAddress);
        var matchOpt = directoryService.findByEmail(email);
        if (matchOpt.isEmpty() || !"ACTIVE".equals(matchOpt.get().status())) {
            // Non-enumerating response
            return;
        }
        var match = matchOpt.get();
        String rawToken = generateToken();
        String tokenHash = HashChainUtil.sha256Hex(rawToken);
        Instant expiresAt = Instant.now().plus(securityProps.resetTokenTtlMinutes(), ChronoUnit.MINUTES);

        String fullName = TenantContext.runWithTenant(new TenantId(match.tenantId()), () -> {
            String name = tenantJdbc.sql("SELECT full_name FROM users WHERE id = :uid")
                    .param("uid", match.tenantUserId())
                    .query(String.class)
                    .single();

            tenantJdbc.sql("""
                    UPDATE verification_tokens SET used_at = NOW()
                    WHERE user_id = :uid AND token_type = 'PASSWORD_RESET' AND used_at IS NULL
                    """)
                    .param("uid", match.tenantUserId())
                    .update();

            tenantJdbc.sql("""
                    INSERT INTO verification_tokens (id, user_id, token_type, token_hash, expires_at)
                    VALUES (:id, :uid, 'PASSWORD_RESET', :hash, :exp)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("uid", match.tenantUserId())
                    .param("hash", tokenHash)
                    .param("exp", Timestamp.from(expiresAt))
                    .update();

            auditWriter.writeTenantEvent(null, "PASSWORD_RESET_REQUESTED", "user",
                    match.tenantUserId().toString(), null, Map.of("ttlMinutes", securityProps.resetTokenTtlMinutes()),
                    ipAddress, null);
            return name;
        });

        String actionUrl = appProperties.frontendBaseUrl() + "/reset-password?tid="
                + urlEncode(match.tenantId().toString()) + "&token=" + urlEncode(rawToken);

        outboxService.enqueueEmail(
                match.tenantId(),
                email.trim().toLowerCase(),
                "PASSWORD_RESET",
                "Reset your Proofly password",
                Map.of(
                        "fullName", fullName,
                        "actionUrl", actionUrl,
                        "ttlMinutes", String.valueOf(securityProps.resetTokenTtlMinutes())
                ),
                "pw-reset:" + tokenHash
        );
    }

    public void confirmPasswordReset(UUID tenantId, String rawToken, String newPassword, String ipAddress) {
        String tokenHash = HashChainUtil.sha256Hex(rawToken);
        String newPasswordHash = passwordService.hash(newPassword);
        TenantContext.runWithTenant(new TenantId(tenantId), () ->
                confirmResetInTenant(tokenHash, newPasswordHash, ipAddress));
    }

    @Transactional
    protected void confirmResetInTenant(String tokenHash, String newPasswordHash, String ipAddress) {
        TokenRow row = findValidToken(tokenHash, "PASSWORD_RESET");

        tenantJdbc.sql("UPDATE verification_tokens SET used_at = NOW() WHERE id = :id")
                .param("id", row.id())
                .update();

        tenantJdbc.sql("""
                UPDATE users
                SET password_hash = :pwHash,
                    pin_failed_attempts = 0,
                    pin_locked_until = NULL
                WHERE id = :uid
                """)
                .param("pwHash", newPasswordHash)
                .param("uid", row.userId())
                .update();

        // Revoke all active refresh token families across all devices on password reset
        tenantJdbc.sql("""
                UPDATE refresh_token_families
                SET revoked_at = NOW()
                WHERE user_id = :uid AND revoked_at IS NULL
                """)
                .param("uid", row.userId())
                .update();

        auditWriter.writeTenantEvent(null, "PASSWORD_RESET_COMPLETED", "user",
                row.userId().toString(), null, Map.of("sessionsRevoked", true), ipAddress, null);
    }

    private TokenRow findValidToken(String tokenHash, String expectedType) {
        TokenRow row = tenantJdbc.sql("""
                SELECT id, user_id, expires_at, used_at
                FROM verification_tokens
                WHERE token_hash = :hash AND token_type = :type
                FOR UPDATE
                """)
                .param("hash", tokenHash)
                .param("type", expectedType)
                .query((rs, rowNum) -> new TokenRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("used_at") != null ? rs.getTimestamp("used_at").toInstant() : null
                ))
                .optional()
                .orElseThrow(() -> new ProoflyException(
                        HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN, "Verification token is invalid"));

        if (row.usedAt() != null) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN, "Token has already been used");
        }
        if (row.expiresAt().isBefore(Instant.now())) {
            throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_TOKEN, "Token has expired");
        }
        return row;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }
}
