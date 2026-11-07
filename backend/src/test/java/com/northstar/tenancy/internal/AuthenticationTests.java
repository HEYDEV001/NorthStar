package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.northstar.PostgresAdmin;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Supabase login (step 1.5): token verification, the per-request membership lookup, and P13. Tokens are signed by
 * {@link TestTokens} and verified by the app's real decoder against the test JWKS.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuthenticationTests.WhoAmI.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final JdbcClient admin;

    @MockitoSpyBean
    MembershipLookup lookup; // a spy, to prove some requests are refused before any database call

    AuthenticationTests(@Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.admin = postgres.jdbc();
    }

    @Test
    void validTokenRunsTheRequestAsTheActiveMember() {
        UUID tenant = tenant();
        UUID user = user();
        membership(tenant, user, "admin", "active");

        assertThat(whoAmI(tokens.forUser(user)))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo(
                        "{\"tenantId\": \"%s\", \"userId\": \"%s\", \"role\": \"admin\"}".formatted(tenant, user));
    }

    @Test
    void missingTokenIs401Unauthorized() {
        MvcTestResult result = mvc.get().uri("/test/whoami").exchange();

        assertThat(result).hasStatus(401).bodyJson().extractingPath("$.code").isEqualTo("unauthorized");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    void expiredTokenIs401() {
        assertInvalid(tokens.token(activeMember()).expired().sign());
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() {
        assertInvalid(tokens.token(activeMember()).signedWithAnotherKey().sign());
    }

    @Test
    void tokenFromAnotherIssuerIs401() {
        assertInvalid(tokens.token(activeMember())
                .claim("iss", "https://evil.example.com/auth/v1")
                .sign());
    }

    @Test
    void tokenForAnotherAudienceIs401() {
        assertInvalid(tokens.token(activeMember()).claim("aud", "anon").sign());
    }

    @Test
    void hs256TokenIs401() {
        assertInvalid(tokens.token(activeMember()).signedWithHs256().sign());
    }

    @Test
    void roleClaimMustBeAuthenticated() {
        assertInvalid(tokens.token(activeMember()).claim("role", "service_role").sign());
        assertInvalid(tokens.token(activeMember()).without("role").sign());
    }

    @Test
    void anonymousUsersAreRejected() {
        assertInvalid(tokens.token(activeMember()).claim("is_anonymous", true).sign());
    }

    @Test
    void subMustBeAUuidAndIsCheckedBeforeAnyDatabaseCall() {
        UUID user = activeMember();
        assertInvalid(tokens.token(user).without("sub").sign());
        assertInvalid(tokens.token(user).claim("sub", "not-a-uuid").sign());
        assertInvalid(tokens.token(user)
                .claim("sub", user.toString().replace("-", ""))
                .sign());

        verify(lookup, never()).activeMembershipOf(any());
    }

    @Test
    void tokensAreNeverLogged(CapturedOutput output) {
        UUID user = activeMember();
        List<String> sent = List.of(
                tokens.forUser(user),
                tokens.token(user).expired().sign(),
                tokens.token(user).signedWithAnotherKey().sign(),
                tokens.token(user).claim("role", "anon").sign());
        sent.forEach(this::whoAmI);

        sent.forEach(token -> assertThat(output.getAll()).doesNotContain(token));
    }

    @Test
    void healthIsPublicWithOrWithoutAToken() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get()
                        .uri("/actuator/health")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user())))
                .hasStatusOk();
    }

    @Test
    void P13_deactivatedUserIsRejectedOnTheNextRequest() {
        UUID tenant = tenant();
        UUID user = user();
        UUID membership = membership(tenant, user, "staff", "active");
        String token = tokens.forUser(user);

        assertThat(whoAmI(token)).hasStatusOk();

        admin.sql("update northstar.memberships set status = 'deactivated' where id = :id")
                .param("id", membership)
                .update();

        assertThat(whoAmI(token)).hasStatus(403).bodyJson().isLenientlyEqualTo("""
                        {"code": "no_active_membership", "message": "This account has no active membership"}""");
    }

    @Test
    void userWithoutAnActiveMembershipIs403() {
        UUID invitedOnly = user();
        membership(tenant(), invitedOnly, "staff", "invited");

        assertThat(whoAmI(tokens.forUser(user())))
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("no_active_membership");
        assertThat(whoAmI(tokens.forUser(invitedOnly)))
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("no_active_membership");
    }

    @Test
    void headerNamingAnotherTenantChangesNothing() {
        UUID tenantA = tenant();
        UUID tenantB = tenant();
        UUID user = user();
        membership(tenantA, user, "admin", "active");
        membership(tenantB, user(), "admin", "active");
        String expected = "{\"tenantId\": \"%s\", \"visibleTenants\": [\"%s\"]}".formatted(tenantA, tenantA);

        assertThat(mvc.post()
                        .uri("/test/whoami?tenantId=" + tenantB + "&tenant_id=" + tenantB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                        .header("X-Tenant-Id", tenantB.toString())
                        .header("X-Northstar-Tenant", tenantB.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\": \"%s\", \"tenant_id\": \"%s\"}".formatted(tenantB, tenantB)))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo(expected);
    }

    private void assertInvalid(String token) {
        assertThat(whoAmI(token)).hasStatus(401).bodyJson().isLenientlyEqualTo("""
                        {"code": "invalid_token", "message": "The access token is invalid or expired"}""");
    }

    private MvcTestResult whoAmI(String token) {
        return mvc.get()
                .uri("/test/whoami")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    /** A test endpoint that reports who the request runs as and which tenants it can see. */
    @RestController
    static class WhoAmI {

        private final TenantRepository tenants;

        WhoAmI(TenantRepository tenants) {
            this.tenants = tenants;
        }

        @GetMapping("/test/whoami")
        Map<String, Object> get() {
            TenantContext context = TenantScope.require();
            return Map.of(
                    "tenantId", context.tenantId(),
                    "userId", context.userId(),
                    "role", context.role().databaseValue(),
                    "visibleTenants",
                            tenants.findAll().stream().map(Tenant::getId).toList());
        }

        @PostMapping("/test/whoami")
        Map<String, Object> post(
                @RequestParam Map<String, String> query, @RequestBody(required = false) Map<String, Object> body) {
            return get(); // whatever the client sends, the tenant comes from the membership lookup
        }
    }

    // Fixtures, written as postgres (bypasses RLS).

    private UUID activeMember() {
        UUID user = user();
        membership(tenant(), user, "staff", "active");
        return user;
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.tenants (id, name, slug) values (:id, 'Test business', :slug)")
                .param("id", id)
                .param("slug", "slug-" + id)
                .update();
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        admin.sql("insert into northstar.user_profiles (id) values (:id)")
                .param("id", id)
                .update();
        return id;
    }

    private UUID membership(UUID tenant, UUID user, String role, String status) {
        return admin.sql("""
                        insert into northstar.memberships (tenant_id, user_id, role, status)
                        values (:tenant, :user, :role, :status) returning id""")
                .param("tenant", tenant)
                .param("user", user)
                .param("role", role)
                .param("status", status)
                .query(UUID.class)
                .single();
    }
}
