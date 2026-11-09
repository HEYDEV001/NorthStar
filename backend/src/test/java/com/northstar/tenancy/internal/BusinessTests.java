package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Creating a business and GET /api/v1/me (step 1.7), through the real login path with test-signed tokens. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BusinessTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final JdbcClient admin;
    private final AppUserDatabase app;
    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final AuditLogRepository auditLog;
    private final UserProfileRepository profiles;

    BusinessTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired PostgresAdmin postgres,
            @Autowired AppUserDatabase app,
            @Autowired TenantRepository tenants,
            @Autowired MembershipRepository memberships,
            @Autowired AuditLogRepository auditLog,
            @Autowired UserProfileRepository profiles) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
        this.app = app;
        this.tenants = tenants;
        this.memberships = memberships;
        this.auditLog = auditLog;
        this.profiles = profiles;
    }

    @Test
    void creatingABusinessMakesTheCallerItsAdmin() {
        UUID user = UUID.randomUUID(); // signed in, but no profile and no membership yet
        String name = uniqueName("Reelrr Studios");

        MvcTestResult created = create(user, "{\"name\": \"%s\"}".formatted(name));

        assertThat(created).hasStatus(201).bodyJson().extractingPath("$.role").isEqualTo("admin");
        assertThat(created).bodyJson().extractingPath("$.business.slug").isEqualTo(Slugs.fromName(name));
        assertThat(created).bodyJson().extractingPath("$.business.status").isEqualTo("onboarding");
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/me");

        UUID tenant = tenantNamed(name);
        assertThat(admin.sql("""
                        select m.role || ',' || m.status || ',' || (p.id is not null) || ',' ||
                               (select count(*) from northstar.audit_log a
                                where a.tenant_id = m.tenant_id and a.action = 'business.created'
                                  and a.actor_user_id = :user and a.target_id = m.tenant_id)
                        from northstar.memberships m
                        left join northstar.user_profiles p on p.id = m.user_id
                        where m.tenant_id = :tenant and m.user_id = :user""")
                        .param("tenant", tenant)
                        .param("user", user)
                        .query(String.class)
                        .single())
                .isEqualTo("admin,active,true,1");

        assertThat(me(user)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"user": {"id": "%s", "email": "test@example.com", "fullName": null},
                         "business": {"id": "%s", "name": "%s", "status": "onboarding"},
                         "role": "admin"}""".formatted(user, tenant, name));
    }

    @Test
    void anExistingMemberGets409AlreadyHasBusinessNeverA500() {
        for (String role : new String[] {"admin", "staff"}) {
            TestData.Member member = data.member(role);
            String name = uniqueName("Second Business");

            assertThat(create(member.userId(), "{\"name\": \"%s\"}".formatted(name)))
                    .as(role)
                    .hasStatus(409)
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"code": "already_has_business", "message": "This account already belongs to a business"}""");
            assertThat(countTenantsNamed(name)).isZero();
        }
    }

    @Test
    void creatingASecondBusinessIs409() {
        UUID user = UUID.randomUUID();
        assertThat(create(user, "{\"name\": \"%s\"}".formatted(uniqueName("First"))))
                .hasStatus(201);

        assertThat(create(user, "{\"name\": \"%s\"}".formatted(uniqueName("Second"))))
                .hasStatus(409);
    }

    @Test
    void twoConcurrentCreatesByOneUserMakeOneBusiness() throws Exception {
        UUID user = UUID.randomUUID();
        String name = uniqueName("Race");
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return create(user, "{\"name\": \"%s\"}".formatted(name))
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(countTenantsNamed(name)).isOne();
        assertThat(admin.sql("select count(*) from northstar.memberships where user_id = :user")
                        .param("user", user)
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    @Test
    void aSuppliedSlugThatIsTakenIs409SlugTaken() {
        UUID existing = data.tenant();
        String takenSlug = "slug-" + existing;

        assertThat(create(UUID.randomUUID(), "{\"name\": \"Anything\", \"slug\": \"%s\"}".formatted(takenSlug)))
                .hasStatus(409)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "slug_taken", "message": "That slug is already taken"}""");
    }

    @Test
    void aReservedSlugIs409SlugReserved() {
        for (String reserved : new String[] {"admin", "www", "dashboard"}) {
            assertThat(create(UUID.randomUUID(), "{\"name\": \"Anything\", \"slug\": \"%s\"}".formatted(reserved)))
                    .as(reserved)
                    .hasStatus(409)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("slug_reserved");
        }
    }

    @Test
    void aTakenGeneratedSlugGetsTheNextNumber() {
        String name = uniqueName("Numbered");
        String base = Slugs.fromName(name);
        insertTenantWithSlug(base);
        insertTenantWithSlug(base + "-2");

        assertThat(create(UUID.randomUUID(), "{\"name\": \"%s\"}".formatted(name)))
                .hasStatus(201)
                .bodyJson()
                .extractingPath("$.business.slug")
                .isEqualTo(base + "-3");
    }

    @Test
    void afterTwentyTakenNumbersTheGeneratedSlugGetsARandomSuffix() {
        String name = uniqueName("Crowded");
        String base = Slugs.fromName(name);
        insertTenantWithSlug(base);
        for (int n = 2; n <= Slugs.MAX_NUMBERED_VARIANT; n++) insertTenantWithSlug(base + "-" + n);

        MvcTestResult created = create(UUID.randomUUID(), "{\"name\": \"%s\"}".formatted(name));

        assertThat(created).hasStatus(201);
        assertThat(slugOf(created)).matches(base + "-[a-z0-9]{6}");
    }

    @Test
    void aNameThatGeneratesAReservedSlugGetsANumber() {
        MvcTestResult created = create(UUID.randomUUID(), "{\"name\": \"Support\"}");
        assertThat(created).hasStatus(201);
        assertThat(slugOf(created)).matches("support-\\d+");
    }

    @Test
    void aNonLatinNameGetsAFallbackSlug() {
        MvcTestResult created = create(UUID.randomUUID(), "{\"name\": \"रील स्टूडियो\"}");
        assertThat(created).hasStatus(201);
        assertThat(slugOf(created)).matches("business-[a-z0-9]{6}");
    }

    @Test
    void invalidRequestsAre400() {
        assertThat(create(UUID.randomUUID(), "{\"name\": \"Shoot\", \"slug\": \"Bad_Slug\"}"))
                .hasStatus(400)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "validation_failed", "details": {"fields": [{"field": "slug", "rule": "Pattern"}]}}""");
        assertThat(create(UUID.randomUUID(), "{\"name\": \"  \"}"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("validation_failed");
        assertThat(create(UUID.randomUUID(), "{\"name\": \"Shoot\", \"status\": \"active\"}"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed_request");
    }

    @Test
    void meWithoutAMembershipReturnsTheUserAndNoBusiness() {
        UUID user = UUID.randomUUID();
        assertThat(me(user)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"user": {"id": "%s", "email": "test@example.com", "fullName": null},
                         "business": null, "role": null}""".formatted(user));
    }

    @Test
    void meForAMemberReturnsTheirBusinessAndRole() {
        TestData.Member member = data.member("staff");
        admin.sql("update northstar.user_profiles set full_name = 'Asha Rao' where id = :id")
                .param("id", member.userId())
                .update();

        assertThat(me(member.userId())).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"user": {"id": "%s", "fullName": "Asha Rao"},
                         "business": {"id": "%s", "slug": "slug-%s", "status": "onboarding"},
                         "role": "staff"}""".formatted(
                        member.userId(), member.tenantId(), member.tenantId()));
    }

    @Test
    void theNewBusinessIsInvisibleToAnotherTenant() {
        UUID creator = UUID.randomUUID();
        String name = uniqueName("Private");
        assertThat(create(creator, "{\"name\": \"%s\"}".formatted(name))).hasStatus(201);
        UUID created = tenantNamed(name);
        TestData.Member outsider = data.member("admin");

        TenantScope.run(new TenantContext(outsider.tenantId(), outsider.userId(), Role.ADMIN), () -> {
            assertThat(tenants.findById(created)).isEmpty();
            assertThat(memberships.findAll()).noneMatch(m -> m.getTenantId().equals(created));
            assertThat(auditLog.findAll()).isEmpty();
            assertThat(profiles.findById(creator)).isEmpty();
        });
        int visibleByNativeQuery = app.as(
                outsider.tenantId(),
                outsider.userId(),
                "admin",
                jdbc -> jdbc.sql("select count(*) from northstar.tenants where id = :id")
                        .param("id", created)
                        .query(Integer.class)
                        .single());
        assertThat(visibleByNativeQuery).isZero();
    }

    @Test
    void theDatabaseRejectsTheNilTenantIdAndMalformedSlugs() {
        assertCheckViolation("insert into northstar.tenants (id, name, slug)"
                + " values ('00000000-0000-0000-0000-000000000000', 'Nil', 'nil-tenant')");
        assertCheckViolation(
                "insert into northstar.tenants (id, name, slug) values (gen_random_uuid(), 'Bad', 'Bad Slug')");
        assertCheckViolation(
                "insert into northstar.tenants (id, name, slug) values (gen_random_uuid(), 'Short', 'ab')");
    }

    @Test
    void slugIsTakenAnswersYesOrNoAndOnlyAppUserMayCallIt() {
        UUID tenant = data.tenant();
        TestData.Member member = data.member("staff");

        Boolean taken = app.as(
                member.tenantId(),
                member.userId(),
                "staff",
                jdbc -> jdbc.sql("select northstar.slug_is_taken(:slug)")
                        .param("slug", "slug-" + tenant)
                        .query(Boolean.class)
                        .single());
        Boolean free = app.as(
                member.tenantId(),
                member.userId(),
                "staff",
                jdbc -> jdbc.sql("select northstar.slug_is_taken('nobody-has-this-slug')")
                        .query(Boolean.class)
                        .single());

        assertThat(taken).isTrue();
        assertThat(free).isFalse();
        assertThat(admin.sql("""
                        select string_agg(r, ',' order by r)
                        from unnest(array['app_user', 'app_admin', 'public', 'anon', 'authenticated']) r
                        where has_function_privilege(r, 'northstar.slug_is_taken(text)', 'EXECUTE')""").query(String.class).single()).isEqualTo("app_user");
    }

    private MvcTestResult create(UUID user, String json) {
        return mvc.post()
                .uri("/api/v1/businesses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult me(UUID user) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .exchange();
    }

    private static String uniqueName(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID tenantNamed(String name) {
        return admin.sql("select id from northstar.tenants where name = :name")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private int countTenantsNamed(String name) {
        return admin.sql("select count(*) from northstar.tenants where name = :name")
                .param("name", name)
                .query(Integer.class)
                .single();
    }

    private void insertTenantWithSlug(String slug) {
        admin.sql("insert into northstar.tenants (id, name, slug) values (gen_random_uuid(), 'Existing', :slug)")
                .param("slug", slug)
                .update();
    }

    private static String slugOf(MvcTestResult result) {
        try {
            String body = result.getResponse().getContentAsString();
            return body.replaceAll(".*\"slug\":\"([^\"]+)\".*", "$1");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertCheckViolation(String sql) {
        try {
            admin.sql(sql).update();
            throw new AssertionError("Expected a check violation for: " + sql);
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
            assertThat(cause).as(sql).isNotNull();
            assertThat(((SQLException) cause).getSQLState()).as(sql).isEqualTo("23514");
        }
    }
}
