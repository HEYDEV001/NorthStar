package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A request that reaches the database without a tenant context gets a 500 and an error log, never a quiet result. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MissingTenantContextHandlingTests.ForgetfulEndpoint.class})
@ExtendWith(OutputCaptureExtension.class)
class MissingTenantContextHandlingTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final JdbcClient admin;

    MissingTenantContextHandlingTests(
            @Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.admin = postgres.jdbc();
    }

    @Test
    void P10_requestWithoutTenantContextFailsWith500AndErrorLog(CapturedOutput output) {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        admin.sql("insert into northstar.tenants (id, name, slug) values (:id, 'Test business', :slug)")
                .param("id", tenant)
                .param("slug", "slug-" + tenant)
                .update();
        admin.sql("insert into northstar.user_profiles (id) values (:id)")
                .param("id", user)
                .update();
        admin.sql("""
                        insert into northstar.memberships (tenant_id, user_id, role, status)
                        values (:tenant, :user, 'staff', 'active')""").param("tenant", tenant).param("user", user).update();

        assertThat(mvc.get()
                        .uri("/test/forgot-tenant-context")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user)))
                .hasStatus(500)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "internal_error", "message": "Internal error", "details": {}}""");
        assertThat(output.getOut()).contains("ERROR").contains("Database access without tenant context");
    }

    /**
     * A deliberately buggy endpoint: the request runs in the member's tenant scope, but it hands database work to
     * another thread, which does not inherit the context.
     */
    @RestController
    static class ForgetfulEndpoint {

        private final MembershipRepository memberships;

        ForgetfulEndpoint(MembershipRepository memberships) {
            this.memberships = memberships;
        }

        @GetMapping("/test/forgot-tenant-context")
        int countMemberships() {
            return CompletableFuture.supplyAsync(() -> memberships.findAll().size())
                    .join();
        }
    }
}
