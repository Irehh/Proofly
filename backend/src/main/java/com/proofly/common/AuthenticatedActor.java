package com.proofly.common;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.UUID;

/**
 * Shared immutable view of the verified JWT actor bound to the current request thread.
 */
public record AuthenticatedActor(
        UUID userId,
        UUID tenantId,
        UUID membershipId,
        String role
) {
    private static final Map<String, Integer> ROLE_RANK = Map.of(
            "EMPLOYEE", 1,
            "MANAGER", 2,
            "ADMIN", 3,
            "SUPER_ADMIN", 4
    );

    public boolean hasAtLeastRole(String minimumRole) {
        Integer actual = ROLE_RANK.get(role);
        Integer required = ROLE_RANK.get(minimumRole);
        return actual != null && required != null && actual >= required;
    }

    public boolean isManagerOrAbove() {
        return hasAtLeastRole("MANAGER");
    }

    public boolean isAdminOrAbove() {
        return hasAtLeastRole("ADMIN");
    }

    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(role);
    }

    public static AuthenticatedActor requireCurrent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !jwtAuth.isAuthenticated()) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                    "Authentication is required");
        }
        var jwt = jwtAuth.getToken();
        try {
            return new AuthenticatedActor(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("tid")),
                    UUID.fromString(jwt.getClaimAsString("mid")),
                    jwt.getClaimAsString("role")
            );
        } catch (Exception e) {
            throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                    "Access token is missing required tenant claims");
        }
    }
}
