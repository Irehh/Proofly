package com.proofly.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "proofly.security")
public record SecurityProperties(
        JwtProps jwt,
        PinProps pin,
        long resetTokenTtlMinutes,
        long verifyTokenTtlHours,
        String piiKeyBase64,
        List<String> corsAllowedOrigins
) {
    public record JwtProps(
            String issuer,
            String keyId,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            String cookieName,
            boolean cookieSecure,
            String cookieSameSite
    ) {}

    public record PinProps(
            int maxAttempts,
            int lockoutMinutes
    ) {}
}
