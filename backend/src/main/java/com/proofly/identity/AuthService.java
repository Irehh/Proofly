package com.proofly.identity;

import com.proofly.audit.AuditWriter;
import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.proofly.tenancy.TenantContext;
import com.proofly.tenancy.TenantDirectoryService;
import com.proofly.tenancy.TenantProvisioningService;
import com.proofly.tenancy.TenantSummary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    public record IssuedSession(AuthDtos.AuthResponse response, String rawRefreshToken, Instant refreshExpiresAt) {}

    private record TenantUserRecord(
            UUID userId,
            UUID membershipId,
            String fullName,
            String email,
            String phoneE164,
            String passwordHash,
            String pinHash,
            int pinFailedAttempts,
            Instant pinLockedUntil,
            boolean mfaEnabled,
            byte[] mfaSecretEncrypted,
            String userStatus,
            String role,
            String membershipStatus
    ) {}

    private final TenantProvisioningService provisioningService;
    private final TenantDirectoryService directoryService;
    private final Argon2PasswordService passwordService;
    private final JwtKeyService jwtKeyService;
    private final RefreshTokenService refreshTokenService;
    private final DeviceService deviceService;
    private final MfaService mfaService;
    private final TokenDenylistAndRateLimitService rateLimitService;
    private final SecurityProperties props;
    private final AuditWriter auditWriter;
    private final JdbcClient tenantJdbc;
    private final com.proofly.common.PiiCrypto piiCrypto;

    public AuthService(TenantProvisioningService provisioningService,
                       TenantDirectoryService directoryService,
                       Argon2PasswordService passwordService,
                       JwtKeyService jwtKeyService,
                       RefreshTokenService refreshTokenService,
                       DeviceService deviceService,
                       MfaService mfaService,
                       TokenDenylistAndRateLimitService rateLimitService,
                       SecurityProperties props,
                       AuditWriter auditWriter,
                       DataSource tenantRoutingDataSource,
                       com.proofly.common.PiiCrypto piiCrypto) {
        this.provisioningService = provisioningService;
        this.directoryService = directoryService;
        this.passwordService = passwordService;
        this.jwtKeyService = jwtKeyService;
        this.refreshTokenService = refreshTokenService;
        this.deviceService = deviceService;
        this.mfaService = mfaService;
        this.rateLimitService = rateLimitService;
        this.props = props;
        this.auditWriter = auditWriter;
        this.tenantJdbc = JdbcClient.create(tenantRoutingDataSource);
        this.piiCrypto = piiCrypto;
    }

    public IssuedSession signupOrganization(AuthDtos.SignupOrgRequest req, String ipAddress) {
        rateLimitService.checkAuthRateLimit("signup:" + ipAddress);
        String passwordHash = passwordService.hash(req.password());

        TenantProvisioningService.ProvisionResult provisioned = provisioningService.provisionNewOrganization(
                new TenantProvisioningService.ProvisionRequest(
                        req.orgName(),
                        req.slug(),
                        req.defaultTimezone(),
                        req.fullName(),
                        req.email(),
                        req.phoneE164(),
                        passwordHash
                )
        );

        auditWriter.writePlatformEvent(
                provisioned.ownerUserId().toString(),
                "TENANT_OWNER",
                provisioned.tenantId(),
                "ORGANIZATION_SIGNUP",
                "tenant",
                provisioned.tenantId().toString(),
                null,
                Map.of("slug", provisioned.slug(), "schemaVersion", provisioned.schemaVersion()),
                ipAddress
        );

        return TenantContext.runWithTenant(new TenantId(provisioned.tenantId()), () -> {
            String platform = toDevicePlatform(req.clientType());
            UUID deviceId = deviceService.registerOrTouchDevice(
                    provisioned.ownerUserId(), req.deviceFingerprint(), req.deviceName(), platform);
            RefreshTokenService.IssuedRefresh refresh = refreshTokenService.createFamily(
                    provisioned.ownerUserId(), deviceId);
            String jti = UUID.randomUUID().toString();
            String accessToken = jwtKeyService.issueAccessToken(
                    provisioned.ownerUserId(), provisioned.tenantId(), provisioned.ownerMembershipId(), "SUPER_ADMIN", jti);

            auditWriter.writeTenantEvent(
                    provisioned.ownerMembershipId(),
                    "USER_SIGNED_UP",
                    "user",
                    provisioned.ownerUserId().toString(),
                    null,
                    Map.of("role", "SUPER_ADMIN"),
                    ipAddress,
                    deviceId
            );

            boolean isWeb = req.clientType() == null || req.clientType() == AuthDtos.ClientType.WEB;
            AuthDtos.AuthResponse body = new AuthDtos.AuthResponse(
                    accessToken,
                    Math.min(900L, props.jwt().accessTokenTtlSeconds()),
                    "Bearer",
                    isWeb ? null : refresh.rawToken(),
                    refresh.expiresAt(),
                    deviceId,
                    new AuthDtos.AuthUserView(
                            provisioned.ownerUserId(),
                            provisioned.tenantId(),
                            provisioned.ownerMembershipId(),
                            req.fullName().trim(),
                            req.email().trim().toLowerCase(),
                            req.phoneE164(),
                            "SUPER_ADMIN",
                            false
                    )
            );
            return new IssuedSession(body, refresh.rawToken(), refresh.expiresAt());
        });
    }

    public IssuedSession login(AuthDtos.LoginRequest req, String ipAddress) {
        AuthDtos.LoginMethod method = req.method() != null ? req.method() : AuthDtos.LoginMethod.EMAIL_PASSWORD;
        rateLimitService.checkAuthRateLimit("login:" + ipAddress);

        TenantSummary.DirectoryMatch match;
        if (method == AuthDtos.LoginMethod.PHONE_PIN) {
            if (req.phoneE164() == null || req.pin() == null) {
                throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                        "Phone number and PIN are required");
            }
            rateLimitService.checkPinRateLimit(req.phoneE164() + ":" + ipAddress);
            match = directoryService.findByPhone(req.phoneE164())
                    .orElseThrow(() -> new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                            "Invalid phone or PIN"));
        } else {
            if (req.email() == null || req.password() == null) {
                throw new ProoflyException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                        "Email and password are required");
            }
            match = directoryService.findByEmail(req.email())
                    .orElseThrow(() -> new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                            "Invalid email or password"));
        }

        if (!"ACTIVE".equals(match.status())) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "User account is disabled");
        }

        return TenantContext.runWithTenant(new TenantId(match.tenantId()), () -> {
            TenantUserRecord user = tenantJdbc.sql("""
                    SELECT u.id AS user_id, m.id AS membership_id, u.full_name, u.email, u.phone_e164,
                           u.password_hash, u.pin_hash, u.pin_failed_attempts, u.pin_locked_until,
                           u.mfa_enabled, u.mfa_secret_encrypted, u.status AS user_status,
                           m.role, m.status AS membership_status
                    FROM users u
                    JOIN memberships m ON m.user_id = u.id
                    WHERE u.id = :uid
                    """)
                    .param("uid", match.tenantUserId())
                    .query((rs, rowNum) -> new TenantUserRecord(
                            rs.getObject("user_id", UUID.class),
                            rs.getObject("membership_id", UUID.class),
                            rs.getString("full_name"),
                            rs.getString("email"),
                            rs.getString("phone_e164"),
                            rs.getString("password_hash"),
                            rs.getString("pin_hash"),
                            rs.getInt("pin_failed_attempts"),
                            rs.getTimestamp("pin_locked_until") != null ? rs.getTimestamp("pin_locked_until").toInstant() : null,
                            rs.getBoolean("mfa_enabled"),
                            rs.getBytes("mfa_secret_encrypted"),
                            rs.getString("user_status"),
                            rs.getString("role"),
                            rs.getString("membership_status")
                    ))
                    .optional()
                    .orElseThrow(() -> new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                            "Invalid credentials"));

            if (!"ACTIVE".equals(user.userStatus()) || !"ACTIVE".equals(user.membershipStatus())) {
                throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Membership is not active");
            }

            if (method == AuthDtos.LoginMethod.PHONE_PIN) {
                verifyPinWithLockout(user, req.pin(), ipAddress);
            } else {
                if (!passwordService.matches(req.password(), user.passwordHash())) {
                    throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                            "Invalid email or password");
                }
            }

            if (user.mfaEnabled()) {
                String secret = piiCrypto.decrypt(user.mfaSecretEncrypted());
                if (req.totpCode() == null || !mfaService.verifyTotp(secret, req.totpCode())) {
                    throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                            "Valid TOTP verification code is required");
                }
            }

            String platform = toDevicePlatform(req.clientType());
            UUID deviceId = deviceService.registerOrTouchDevice(
                    user.userId(), req.deviceFingerprint(), req.deviceName(), platform);
            RefreshTokenService.IssuedRefresh refresh = refreshTokenService.createFamily(user.userId(), deviceId);
            String accessToken = jwtKeyService.issueAccessToken(
                    user.userId(), match.tenantId(), user.membershipId(), user.role(), UUID.randomUUID().toString());

            auditWriter.writeTenantEvent(user.membershipId(), "USER_LOGGED_IN", "user",
                    user.userId().toString(), null, Map.of("method", method.name()), ipAddress, deviceId);

            boolean isWeb = req.clientType() == null || req.clientType() == AuthDtos.ClientType.WEB;
            AuthDtos.AuthResponse body = new AuthDtos.AuthResponse(
                    accessToken,
                    Math.min(900L, props.jwt().accessTokenTtlSeconds()),
                    "Bearer",
                    isWeb ? null : refresh.rawToken(),
                    refresh.expiresAt(),
                    deviceId,
                    new AuthDtos.AuthUserView(
                            user.userId(),
                            match.tenantId(),
                            user.membershipId(),
                            user.fullName(),
                            user.email(),
                            user.phoneE164(),
                            user.role(),
                            user.mfaEnabled()
                    )
            );
            return new IssuedSession(body, refresh.rawToken(), refresh.expiresAt());
        });
    }

    public IssuedSession refresh(String rawRefreshToken, AuthDtos.ClientType clientType, String ipAddress) {
        RefreshTokenService.RotatedSession rotated = refreshTokenService.rotate(rawRefreshToken, ipAddress);
        String accessToken = jwtKeyService.issueAccessToken(
                rotated.userId(), rotated.tenantId(), rotated.membershipId(), rotated.role(), UUID.randomUUID().toString());

        AuthDtos.AuthUserView userView = TenantContext.runWithTenant(new TenantId(rotated.tenantId()), () ->
                tenantJdbc.sql("SELECT full_name, email, phone_e164, mfa_enabled FROM users WHERE id = :uid")
                        .param("uid", rotated.userId())
                        .query((rs, rowNum) -> new AuthDtos.AuthUserView(
                                rotated.userId(),
                                rotated.tenantId(),
                                rotated.membershipId(),
                                rs.getString("full_name"),
                                rs.getString("email"),
                                rs.getString("phone_e164"),
                                rotated.role(),
                                rs.getBoolean("mfa_enabled")
                        ))
                        .single()
        );

        boolean isWeb = clientType == null || clientType == AuthDtos.ClientType.WEB;
        AuthDtos.AuthResponse body = new AuthDtos.AuthResponse(
                accessToken,
                Math.min(900L, props.jwt().accessTokenTtlSeconds()),
                "Bearer",
                isWeb ? null : rotated.newRawRefreshToken(),
                rotated.expiresAt(),
                rotated.deviceId(),
                userView
        );
        return new IssuedSession(body, rotated.newRawRefreshToken(), rotated.expiresAt());
    }

    public void logout(String rawRefreshToken, String accessJti, Instant accessExp) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenService.revokeFamilyByToken(rawRefreshToken);
        }
        if (accessJti != null && accessExp != null) {
            Duration remaining = Duration.between(Instant.now(), accessExp);
            if (!remaining.isNegative() && !remaining.isZero()) {
                rateLimitService.denyAccessToken(accessJti, remaining);
            }
        }
    }

    private void verifyPinWithLockout(TenantUserRecord user, String rawPin, String ipAddress) {
        if (user.pinLockedUntil() != null && user.pinLockedUntil().isAfter(Instant.now())) {
            throw new ProoflyException(HttpStatus.LOCKED, ErrorCode.PIN_LOCKED,
                    "PIN login is temporarily locked due to repeated failed attempts");
        }
        if (!passwordService.matches(rawPin, user.pinHash())) {
            int nextAttempts = user.pinFailedAttempts() + 1;
            Instant lockUntil = nextAttempts >= props.pin().maxAttempts()
                    ? Instant.now().plus(Duration.ofMinutes(props.pin().lockoutMinutes()))
                    : null;
            tenantJdbc.sql("""
                    UPDATE users
                    SET pin_failed_attempts = :attempts,
                        pin_locked_until = :lockedUntil
                    WHERE id = :uid
                    """)
                    .param("attempts", nextAttempts)
                    .param("lockedUntil", lockUntil != null ? java.sql.Timestamp.from(lockUntil) : null)
                    .param("uid", user.userId())
                    .update();

            if (lockUntil != null) {
                auditWriter.writeTenantEvent(user.membershipId(), "PIN_LOCKED_OUT", "user",
                        user.userId().toString(), null, Map.of("attempts", nextAttempts), ipAddress, null);
                throw new ProoflyException(HttpStatus.LOCKED, ErrorCode.PIN_LOCKED,
                        "PIN login locked due to repeated failed attempts");
            }
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid phone or PIN");
        }
        if (user.pinFailedAttempts() > 0 || user.pinLockedUntil() != null) {
            tenantJdbc.sql("UPDATE users SET pin_failed_attempts = 0, pin_locked_until = NULL WHERE id = :uid")
                    .param("uid", user.userId())
                    .update();
        }
    }

    private static String toDevicePlatform(AuthDtos.ClientType clientType) {
        if (clientType == null || clientType == AuthDtos.ClientType.WEB) {
            return "WEB_PWA";
        }
        return clientType == AuthDtos.ClientType.API ? "API" : "ANDROID";
    }
}
