package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs every authenticated request as the user's active membership (step 1.5): the user comes from the verified
 * token's sub, the tenant and role from the membership lookup, never from anything the client sends. The rest of the
 * request runs inside {@link TenantScope}, which is cleared afterwards. No active membership is a 403, except on the
 * membership-optional endpoints, which run without a tenant scope.
 *
 * <p>Built by {@link SecurityConfiguration} for the security filter chain only, never registered as a servlet filter.
 */
final class TenantContextFilter extends OncePerRequestFilter {

    private final MembershipLookup lookup;
    private final RequestMatcher publicPaths;
    private final RequestMatcher membershipOptional;

    TenantContextFilter(MembershipLookup lookup, RequestMatcher publicPaths, RequestMatcher membershipOptional) {
        this.lookup = lookup;
        this.publicPaths = publicPaths;
        this.membershipOptional = membershipOptional;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (publicPaths.matches(request) || !(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }

        UUID userId = UUID.fromString(token.getToken().getSubject()); // a UUID: checked when the token was decoded
        Optional<MembershipLookup.ActiveMembership> membership = lookup.activeMembershipOf(userId);
        if (membership.isEmpty()) {
            if (membershipOptional.matches(request)) {
                chain.doFilter(request, response);
            } else {
                ApiErrors.write(
                        response,
                        HttpServletResponse.SC_FORBIDDEN,
                        "no_active_membership",
                        "This account has no active membership");
            }
            return;
        }

        TenantContext context = new TenantContext(
                membership.get().tenantId(), userId, membership.get().role());
        try {
            TenantScope.run(context, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new ChainFailure(e);
                }
            });
        } catch (ChainFailure failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw (ServletException) failure.getCause();
        }
    }

    /** Carries a checked exception from the filter chain out through TenantScope. */
    private static final class ChainFailure extends RuntimeException {
        ChainFailure(Exception cause) {
            super(cause);
        }
    }
}
