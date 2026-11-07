package com.northstar.tenancy.internal;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * What a Supabase access token must prove before Northstar trusts it (step 1.5): the standard checks (expiry and
 * not-before with Spring's default clock skew, issuer), plus audience, the authenticated role, not an anonymous user,
 * and a sub that is a UUID. Every check runs before any database call; a failure is a 401 invalid_token.
 */
final class SupabaseTokenValidators {

    private SupabaseTokenValidators() {}

    static OAuth2TokenValidator<Jwt> of(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Collection<String>>(
                        JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>("role", "authenticated"::equals),
                new JwtClaimValidator<Object>("is_anonymous", value -> !Boolean.TRUE.equals(value)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, SupabaseTokenValidators::isUuid));
    }

    static boolean isUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
