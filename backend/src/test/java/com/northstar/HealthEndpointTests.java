package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthEndpointTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final JdbcClient admin;

    HealthEndpointTests(@Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.admin = postgres.jdbc();
    }

    @Test
    void healthIsUpWithDatabaseConnected() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void healthHidesComponentDetails() {
        assertThat(mvc.get().uri("/actuator/health")).bodyJson().doesNotHavePath("$.components");
    }

    @Test
    void otherActuatorEndpointsAreNotPublic() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(401);
    }

    /** Only health is exposed: even a signed-in member finds nothing else under /actuator. */
    @Test
    void otherActuatorEndpointsDoNotExistForAMember() {
        String token = tokens.forUser(activeMember());
        for (String endpoint : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops")) {
            assertThat(mvc.get().uri(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .as(endpoint)
                    .hasStatus(404);
        }
    }

    private UUID activeMember() {
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
                        values (:tenant, :user, 'admin', 'active')""").param("tenant", tenant).param("user", user).update();
        return user;
    }
}
