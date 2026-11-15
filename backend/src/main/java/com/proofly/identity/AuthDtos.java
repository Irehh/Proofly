package com.proofly.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public enum ClientType { WEB, MOBILE, API }

    public enum LoginMethod { EMAIL_PASSWORD, PHONE_PIN }

    public record SignupOrgRequest(
            @NotBlank @Size(min = 2, max = 160) String orgName,
            @NotBlank @Pattern(regexp = "^[a-z0-9-]{3,50}$") String slug,
            String defaultTimezone,
            @NotBlank @Size(min = 2, max = 160) String fullName,
            @NotBlank @Email String email,
            @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneE164,
            @NotBlank @Size(min = 10, max = 128) String password,
            String deviceFingerprint,
            String deviceName,
            ClientType clientType
    ) {}

    public record LoginRequest(
            LoginMethod method,
            @Email String email,
            @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneE164,
            String password,
            @Pattern(regexp = "^\\d{4,8}$") String pin,
            @Pattern(regexp = "^\\d{6}$") String totpCode,
            String deviceFingerprint,
            String deviceName,
            ClientType clientType
    ) {}

    public record RefreshRequest(
            String refreshToken
    ) {}

    public record LogoutRequest(
            String refreshToken
    ) {}

    public record MfaEnableRequest(
            @NotBlank @Pattern(regexp = "^\\d{6}$") String totpCode
    ) {}

    public record PasswordResetRequest(
            @NotBlank @Email String email
    ) {}

    public record PasswordResetConfirmRequest(
            @jakarta.validation.constraints.NotNull UUID tenantId,
            @NotBlank String token,
            @NotBlank @Size(min = 10, max = 128) String newPassword
    ) {}

    public record EmailVerifyConfirmRequest(
            @jakarta.validation.constraints.NotNull UUID tenantId,
            @NotBlank String token
    ) {}

    public record AuthUserView(
            UUID userId,
            UUID tenantId,
            UUID membershipId,
            String fullName,
            String email,
            String phoneE164,
            String role,
            boolean mfaEnabled
    ) {}

    public record AuthResponse(
            String accessToken,
            long expiresInSeconds,
            String tokenType,
            String refreshToken,
            Instant refreshExpiresAt,
            UUID deviceId,
            AuthUserView user
    ) {}
}
