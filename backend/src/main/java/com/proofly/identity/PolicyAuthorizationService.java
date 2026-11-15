package com.proofly.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Default-deny authorization policy service evaluated by @PreAuthorize expressions.
 */
@Service("policyAuth")
public class PolicyAuthorizationService {

    private static final Map<String, Integer> ROLE_RANK = Map.of(
            "EMPLOYEE", 1,
            "MANAGER", 2,
            "ADMIN", 3,
            "SUPER_ADMIN", 4
    );

    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken token && token.isAuthenticated();
    }

    public boolean hasAtLeastRole(String minimumRole) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !jwtAuth.isAuthenticated()) {
            return false;
        }
        String role = jwtAuth.getToken().getClaimAsString("role");
        if (role == null) {
            return false;
        }
        Integer actualRank = ROLE_RANK.get(role);
        Integer requiredRank = ROLE_RANK.get(minimumRole);
        return actualRank != null && requiredRank != null && actualRank >= requiredRank;
    }

    public boolean isAdminOrAbove() {
        return hasAtLeastRole("ADMIN");
    }
}
