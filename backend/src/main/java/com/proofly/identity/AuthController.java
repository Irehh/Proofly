package com.proofly.identity;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Authentication & Devices", description = "Dual-client web cookie and mobile bearer authentication endpoints")
public class AuthController {

    private final AuthService authService;
    private final DeviceService deviceService;
    private final MfaService mfaService;
    private final JwtKeyService jwtKeyService;
    private final AccountRecoveryService recoveryService;
    private final SecurityProperties props;

    public AuthController(AuthService authService,
                          DeviceService deviceService,
                          MfaService mfaService,
                          JwtKeyService jwtKeyService,
                          AccountRecoveryService recoveryService,
                          SecurityProperties props) {
        this.authService = authService;
        this.deviceService = deviceService;
        this.mfaService = mfaService;
        this.jwtKeyService = jwtKeyService;
        this.recoveryService = recoveryService;
        this.props = props;
    }

    @PostMapping("/api/v1/auth/password-reset/request")
    @Operation(summary = "Request a password reset email (non-enumerating 202 Accepted)")
    public ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody AuthDtos.PasswordResetRequest req,
            HttpServletRequest httpReq
    ) {
        recoveryService.requestPasswordReset(req.email(), extractClientIp(httpReq));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/auth/password-reset/confirm")
    @Operation(summary = "Confirm password reset with single-use token and revoke active sessions")
    public ResponseEntity<Void> confirmPasswordReset(
            @Valid @RequestBody AuthDtos.PasswordResetConfirmRequest req,
            HttpServletRequest httpReq
    ) {
        recoveryService.confirmPasswordReset(req.tenantId(), req.token(), req.newPassword(), extractClientIp(httpReq));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/auth/verify-email/confirm")
    @Operation(summary = "Verify user email address using single-use verification token")
    public ResponseEntity<Void> confirmEmailVerification(
            @Valid @RequestBody AuthDtos.EmailVerifyConfirmRequest req,
            HttpServletRequest httpReq
    ) {
        recoveryService.confirmEmailVerification(req.tenantId(), req.token(), extractClientIp(httpReq));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/auth/verify-email/resend")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Re-send email verification link for current authenticated user")
    public ResponseEntity<Void> resendEmailVerification(@AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tid"));
        UUID userId = UUID.fromString(jwt.getSubject());
        recoveryService.resendEmailVerification(tenantId, userId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/api/v1/auth/signup-org")
    @Operation(summary = "Create organization, provision tenant database, and sign in owner")
    public ResponseEntity<AuthDtos.AuthResponse> signupOrg(
            @Valid @RequestBody AuthDtos.SignupOrgRequest request,
            HttpServletRequest httpReq,
            HttpServletResponse httpRes
    ) {
        AuthService.IssuedSession session = authService.signupOrganization(request, extractClientIp(httpReq));
        if (isWebClient(request.clientType())) {
            attachRefreshCookie(httpRes, session.rawRefreshToken(), session.refreshExpiresAt());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(session.response());
    }

    @PostMapping("/api/v1/auth/login")
    @Operation(summary = "Sign in with email/password or phone/PIN")
    public ResponseEntity<AuthDtos.AuthResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request,
            HttpServletRequest httpReq,
            HttpServletResponse httpRes
    ) {
        AuthService.IssuedSession session = authService.login(request, extractClientIp(httpReq));
        if (isWebClient(request.clientType())) {
            attachRefreshCookie(httpRes, session.rawRefreshToken(), session.refreshExpiresAt());
        }
        return ResponseEntity.ok(session.response());
    }

    @PostMapping("/api/v1/auth/refresh")
    @Operation(summary = "Rotate refresh token (supports httpOnly cookie for web or bearer body for mobile)")
    public ResponseEntity<AuthDtos.AuthResponse> refresh(
            @RequestBody(required = false) AuthDtos.RefreshRequest body,
            @RequestHeader(value = "X-Client-Type", required = false) String clientHeader,
            HttpServletRequest httpReq,
            HttpServletResponse httpRes
    ) {
        AuthDtos.ClientType clientType = resolveClientType(clientHeader, body != null && body.refreshToken() != null);
        String rawToken;
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            rawToken = body.refreshToken();
        } else {
            validateCookieOrigin(httpReq);
            rawToken = readRefreshCookie(httpReq);
        }

        AuthService.IssuedSession session = authService.refresh(rawToken, clientType, extractClientIp(httpReq));
        if (isWebClient(clientType)) {
            attachRefreshCookie(httpRes, session.rawRefreshToken(), session.refreshExpiresAt());
        }
        return ResponseEntity.ok(session.response());
    }

    @PostMapping("/api/v1/auth/logout")
    @Operation(summary = "Revoke current refresh token family and denylist active access token")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) AuthDtos.LogoutRequest body,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpReq,
            HttpServletResponse httpRes
    ) {
        String rawToken = (body != null && body.refreshToken() != null)
                ? body.refreshToken()
                : readRefreshCookieOrNull(httpReq);
        if (body == null || body.refreshToken() == null) {
            validateCookieOrigin(httpReq);
        }
        authService.logout(
                rawToken,
                jwt != null ? jwt.getId() : null,
                jwt != null ? jwt.getExpiresAt() : null
        );
        clearRefreshCookie(httpRes);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/devices")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "List registered devices for the authenticated user")
    public List<DeviceService.DeviceView> listDevices(@AuthenticationPrincipal Jwt jwt) {
        return deviceService.listUserDevices(UUID.fromString(jwt.getSubject()));
    }

    @DeleteMapping("/api/v1/devices/{deviceId}")
    @PreAuthorize("@policyAuth.isAuthenticated()")
    @Operation(summary = "Revoke a registered device and invalidate all of its refresh token families")
    public ResponseEntity<Void> revokeDevice(
            @PathVariable UUID deviceId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpReq
    ) {
        deviceService.revokeDevice(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("mid")),
                deviceId,
                extractClientIp(httpReq)
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/auth/mfa/setup")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Initiate TOTP MFA enrollment for ADMIN or SUPER_ADMIN")
    public MfaService.MfaSetupResponse setupMfa(@AuthenticationPrincipal Jwt jwt) {
        return mfaService.beginSetup(UUID.fromString(jwt.getSubject()), jwt.getSubject());
    }

    @PostMapping("/api/v1/auth/mfa/enable")
    @PreAuthorize("@policyAuth.isAdminOrAbove()")
    @Operation(summary = "Verify TOTP code and enable MFA for ADMIN or SUPER_ADMIN")
    public ResponseEntity<Void> enableMfa(
            @Valid @RequestBody AuthDtos.MfaEnableRequest req,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpReq
    ) {
        mfaService.enableMfa(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("mid")),
                req.totpCode(),
                extractClientIp(httpReq)
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/.well-known/jwks.json")
    @Operation(summary = "Publish public RSA JWKs for asymmetric JWT verification")
    public Map<String, Object> jwks() {
        return jwtKeyService.publicJwks();
    }

    private void attachRefreshCookie(HttpServletResponse response, String token, Instant expiresAt) {
        long maxAge = Math.max(0, Duration.between(Instant.now(), expiresAt).getSeconds());
        ResponseCookie cookie = ResponseCookie.from(props.jwt().cookieName(), token)
                .httpOnly(true)
                .secure(props.jwt().cookieSecure())
                .sameSite(props.jwt().cookieSameSite())
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(props.jwt().cookieName(), "")
                .httpOnly(true)
                .secure(props.jwt().cookieSecure())
                .sameSite(props.jwt().cookieSameSite())
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readRefreshCookie(HttpServletRequest req) {
        String value = readRefreshCookieOrNull(req);
        if (value == null || value.isBlank()) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Missing refresh cookie");
        }
        return value;
    }

    private String readRefreshCookieOrNull(HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        return Arrays.stream(req.getCookies())
                .filter(c -> props.jwt().cookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private void validateCookieOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin != null && !props.corsAllowedOrigins().contains(origin)) {
            throw new ProoflyException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Invalid request origin for cookie auth");
        }
    }

    private static boolean isWebClient(AuthDtos.ClientType type) {
        return type == null || type == AuthDtos.ClientType.WEB;
    }

    private static AuthDtos.ClientType resolveClientType(String header, boolean hasBodyToken) {
        if (header != null && !header.isBlank()) {
            try {
                return AuthDtos.ClientType.valueOf(header.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        return hasBodyToken ? AuthDtos.ClientType.MOBILE : AuthDtos.ClientType.WEB;
    }

    private static String extractClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
