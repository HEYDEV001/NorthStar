package com.northstar.tenancy.internal;

import com.northstar.api.ApiError;
import com.northstar.api.ApiErrorMapper;
import com.northstar.api.ApiErrorWriter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestTemplate;

/**
 * Supabase login (step 1.5). Every request except health needs a valid Supabase access token, verified against
 * Supabase's public signing keys with asymmetric algorithms only; then {@link TenantContextFilter} runs it as the
 * user's active membership. Stateless: no sessions, cookies, form login or CSRF.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final RequestMatcher PUBLIC_PATHS =
            PathPatternRequestMatcher.withDefaults().matcher("/actuator/health/**");

    /**
     * Endpoints a signed-in user without an active membership may call; they run without a tenant scope. Members
     * calling them still run in their own scope (creating a second business then answers 409, D4).
     */
    private static final RequestMatcher MEMBERSHIP_OPTIONAL = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/businesses"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/me"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/invites/accept"));

    @Bean
    SecurityFilterChain api(HttpSecurity http, MembershipLookup lookup, ApiErrorMapper errors, ApiErrorWriter writer)
            throws Exception {
        AuthenticationEntryPoint unauthorized =
                (request, response, exception) -> unauthorized(request, response, exception, errors, writer);
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll() // the /error fallback answers in our format; it must never turn into a 401
                        .requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer ->
                        resourceServer.jwt(Customizer.withDefaults()).authenticationEntryPoint(unauthorized))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized))
                .addFilterAfter(
                        new TenantContextFilter(lookup, PUBLIC_PATHS, MEMBERSHIP_OPTIONAL, errors, writer),
                        AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Verifies Supabase tokens: JWKS, ES256 or RS256 only (never HS256 or none), and {@link SupabaseTokenValidators}.
     * Fetching the keys has explicit connect and read timeouts.
     */
    @Bean
    JwtDecoder supabaseJwtDecoder(
            @Value("${northstar.auth.issuer}") String issuer,
            @Value("${northstar.auth.jwk-set-uri}") String jwkSetUri,
            @Value("${northstar.auth.audience}") String audience,
            @Value("${northstar.auth.jwks-connect-timeout}") Duration connectTimeout,
            @Value("${northstar.auth.jwks-read-timeout}") Duration readTimeout) {
        return decoder(issuer, jwkSetUri, audience, connectTimeout, readTimeout);
    }

    static JwtDecoder decoder(
            String issuer, String jwkSetUri, String audience, Duration connectTimeout, Duration readTimeout) {
        requireAbsoluteUrl("northstar.auth.issuer (set SUPABASE_URL)", issuer);
        requireAbsoluteUrl("northstar.auth.jwk-set-uri", jwkSetUri);
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(connectTimeout);
        http.setReadTimeout(readTimeout);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(http))
                .build();
        decoder.setJwtValidator(SupabaseTokenValidators.of(issuer, audience));
        return decoder;
    }

    /** 401 in the {code, message, details} format, keeping the standard WWW-Authenticate header. Never logs the token. */
    private static void unauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception,
            ApiErrorMapper errors,
            ApiErrorWriter writer)
            throws IOException {
        new BearerTokenAuthenticationEntryPoint().commence(request, response, exception);
        ApiError error = exception instanceof OAuth2AuthenticationException
                ? errors.error("invalid_token", "The access token is invalid or expired", Map.of())
                : errors.forStatus(HttpStatus.UNAUTHORIZED);
        writer.write(response, HttpStatus.UNAUTHORIZED, error);
    }

    private static void requireAbsoluteUrl(String name, String value) {
        URI uri = URI.create(value);
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalStateException(name + " must be an absolute URL");
        }
    }
}
