package com.proofly.identity;

import com.proofly.common.ErrorCode;
import com.proofly.common.ProoflyException;
import com.proofly.common.TenantId;
import com.proofly.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds TenantContext exclusively from the verified JWT 'tid' claim (never client headers)
 * and rejects access tokens whose 'jti' is present in the Redis denylist.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    private final TokenDenylistAndRateLimitService denylistService;

    public TenantContextFilter(TokenDenylistAndRateLimitService denylistService) {
        this.denylistService = denylistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken jwtAuth && jwtAuth.isAuthenticated()) {
                String jti = jwtAuth.getToken().getId();
                if (denylistService.isAccessTokenDenied(jti)) {
                    throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_REVOKED,
                            "Access token has been revoked");
                }
                String tid = jwtAuth.getToken().getClaimAsString("tid");
                if (tid == null || tid.isBlank()) {
                    throw new ProoflyException(HttpStatus.UNAUTHORIZED, ErrorCode.TENANT_CONTEXT_MISSING,
                            "JWT missing required tenant claim");
                }
                TenantContext.set(TenantId.of(tid));
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
