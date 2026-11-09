package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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

/** The team endpoints (step 1.8) through the real login path: roles, P9, P13 and P14, audit, idempotency. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MembersTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final JdbcClient admin;

    MembersTests(@Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** A tenant with one admin and one staff member. */
    private record Team(UUID tenant, UUID adminUser, UUID adminMembership, UUID staffUser, UUID staffMembership) {}

    private Team team() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        return new Team(owner.tenantId(), owner.userId(), owner.membershipId(), staffUser, staffMembership);
    }

    @Test
    void theListShowsTheTeamWithNamesRolesAndStatusesButNoPhones() throws Exception {
        Team team = team();
        setName(team.adminUser(), "Asha Rao");
        admin.sql("update northstar.memberships set whatsapp_phone = '+919876543210' where id = :id")
                .param("id", team.staffMembership())
                .update();
        data.member("admin"); // another tenant's member, never listed

        MvcTestResult result = get(team.adminUser(), "/api/v1/members");

        assertThat(result).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        [{"membershipId": "%s", "userId": "%s", "fullName": "Asha Rao", "role": "admin", "status": "active"},
                         {"membershipId": "%s", "userId": "%s", "fullName": null, "role": "staff", "status": "active"}]""".formatted(
                        team.adminMembership(), team.adminUser(), team.staffMembership(), team.staffUser()));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("9876543210", "whatsapp", "phone");
    }

    @Test
    void staffGet403OnEveryAdminOnlyEndpointWhateverTheId() {
        Team team = team();
        UUID foreign = data.member("admin").membershipId();
        for (UUID target : List.of(team.staffMembership(), team.adminMembership(), foreign, UUID.randomUUID())) {
            for (MvcTestResult result : List.of(
                    patch(team.staffUser(), "/api/v1/members/" + target + "/role", "{\"role\": \"admin\"}"),
                    post(team.staffUser(), "/api/v1/members/" + target + "/deactivate"),
                    post(team.staffUser(), "/api/v1/members/" + target + "/reactivate"))) {
                assertThat(result)
                        .hasStatus(403)
                        .bodyJson()
                        .extractingPath("$.code")
                        .isEqualTo("admin_only");
            }
        }
        assertThat(get(team.staffUser(), "/api/v1/members"))
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("admin_only");
        assertThat(status(team.adminMembership())).isEqualTo("admin,active");
    }

    @Test
    void P9_membershipFromAnotherTenantIs404() throws Exception {
        Team team = team();
        UUID foreign = data.member("staff").membershipId();
        UUID missing = UUID.randomUUID();

        for (String action : List.of("role", "deactivate", "reactivate")) {
            String foreignBody = body(memberAction(team.adminUser(), foreign, action));
            String missingBody = body(memberAction(team.adminUser(), missing, action));
            assertThat(memberAction(team.adminUser(), foreign, action))
                    .as(action)
                    .hasStatus(404);
            assertThat(foreignBody).as(action).isEqualTo(missingBody);
        }
        assertThat(status(foreign)).isEqualTo("staff,active");
    }

    @Test
    void anAdminChangesARoleAndItIsAuditedWithCodesOnly() {
        Team team = team();
        setName(team.staffUser(), "Ravi Kumar");

        assertThat(patch(
                        team.adminUser(),
                        "/api/v1/members/" + team.staffMembership() + "/role",
                        "{\"role\": \"admin\"}"))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"membershipId": "%s", "fullName": "Ravi Kumar", "role": "admin", "status": "active"}""".formatted(team.staffMembership()));

        assertThat(audit(team.tenant()))
                .containsExactly("member.role_changed|%s|memberships|%s|{\"to\": \"admin\", \"from\": \"staff\"}"
                        .formatted(team.adminUser(), team.staffMembership()));
    }

    @Test
    void repeatingAChangeIsA200NoOpWithoutANewAuditEntry() {
        Team team = team();
        String role = "/api/v1/members/" + team.staffMembership() + "/role";
        String deactivate = "/api/v1/members/" + team.staffMembership() + "/deactivate";

        assertThat(patch(team.adminUser(), role, "{\"role\": \"staff\"}")).hasStatusOk();
        assertThat(post(team.adminUser(), deactivate)).hasStatusOk();
        assertThat(post(team.adminUser(), deactivate))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("deactivated");

        assertThat(audit(team.tenant())).hasSize(1).first().asString().startsWith("member.deactivated|");
    }

    @Test
    void P14_lastAdminCannotDemoteThemselves() {
        Team team = team();
        assertLastAdmin(patch(
                team.adminUser(), "/api/v1/members/" + team.adminMembership() + "/role", "{\"role\": \"staff\"}"));
        assertThat(status(team.adminMembership())).isEqualTo("admin,active");
        assertThat(audit(team.tenant())).isEmpty();
    }

    @Test
    void P14_lastAdminCannotBeDeactivated() {
        Team team = team();
        assertLastAdmin(post(team.adminUser(), "/api/v1/members/" + team.adminMembership() + "/deactivate"));
        assertThat(status(team.adminMembership())).isEqualTo("admin,active");
    }

    @Test
    void P14_anAdminMayStepDownWhileAnotherAdminRemains() {
        Team team = team();
        UUID secondAdmin = data.user();
        UUID secondAdminMembership = data.membership(team.tenant(), secondAdmin, "admin", "active");

        assertThat(patch(
                        team.adminUser(),
                        "/api/v1/members/" + team.adminMembership() + "/role",
                        "{\"role\": \"staff\"}"))
                .hasStatusOk();
        assertLastAdmin(post(secondAdmin, "/api/v1/members/" + secondAdminMembership + "/deactivate"));
        assertThat(activeAdmins(team.tenant())).isOne();
    }

    @Test
    void P14_twoConcurrentDemotionsLeaveOneAdmin() throws Exception {
        for (int round = 0; round < 5; round++) {
            Team team = team();
            UUID secondAdmin = data.user();
            UUID secondAdminMembership = data.membership(team.tenant(), secondAdmin, "admin", "active");

            List<MvcTestResult> results = together(
                    () -> patch(
                            team.adminUser(),
                            "/api/v1/members/" + secondAdminMembership + "/role",
                            "{\"role\": \"staff\"}"),
                    () -> patch(
                            secondAdmin,
                            "/api/v1/members/" + team.adminMembership() + "/role",
                            "{\"role\": \"staff\"}"));

            assertOneWonAndTheOtherWasRefused(results);
            assertThat(activeAdmins(team.tenant())).as("round " + round).isOne();
        }
    }

    @Test
    void P14_twoConcurrentSelfDeactivationsLeaveOneAdmin() throws Exception {
        for (int round = 0; round < 5; round++) {
            Team team = team();
            UUID secondAdmin = data.user();
            UUID secondAdminMembership = data.membership(team.tenant(), secondAdmin, "admin", "active");

            List<MvcTestResult> results = together(
                    () -> post(team.adminUser(), "/api/v1/members/" + team.adminMembership() + "/deactivate"),
                    () -> post(secondAdmin, "/api/v1/members/" + secondAdminMembership + "/deactivate"));

            assertOneWonAndTheOtherWasRefused(results);
            assertThat(activeAdmins(team.tenant())).as("round " + round).isOne();
        }
    }

    @Test
    void concurrentChangesToTheSameMemberAreBothApplied() throws Exception {
        for (int round = 0; round < 5; round++) {
            Team team = team();
            UUID secondAdmin = data.user();
            data.membership(team.tenant(), secondAdmin, "admin", "active");
            UUID target = data.membership(team.tenant(), data.user(), "admin", "active");

            List<MvcTestResult> results = together(
                    () -> patch(team.adminUser(), "/api/v1/members/" + target + "/role", "{\"role\": \"staff\"}"),
                    () -> post(secondAdmin, "/api/v1/members/" + target + "/deactivate"));

            assertThat(results)
                    .as("round " + round)
                    .allSatisfy(result -> assertThat(result).hasStatusOk());
            assertThat(status(target)).as("round " + round).isEqualTo("staff,deactivated");
            // Each change saw the committed row: the role went admin -> staff, the status active -> deactivated,
            // whichever ran first.
            assertThat(auditFor(team.tenant(), target))
                    .as("round " + round)
                    .containsExactlyInAnyOrder(
                            "member.role_changed|{\"to\": \"staff\", \"from\": \"admin\"}",
                            "member.deactivated|{\"to\": \"deactivated\", \"from\": \"active\"}");
        }
    }

    @Test
    void twoAdminsDeactivatingTheSameMemberAtOnceMakeOneChange() throws Exception {
        for (int round = 0; round < 5; round++) {
            Team team = team();
            UUID secondAdmin = data.user();
            data.membership(team.tenant(), secondAdmin, "admin", "active");

            List<MvcTestResult> results = together(
                    () -> post(team.adminUser(), "/api/v1/members/" + team.staffMembership() + "/deactivate"),
                    () -> post(secondAdmin, "/api/v1/members/" + team.staffMembership() + "/deactivate"));

            assertThat(results)
                    .as("round " + round)
                    .allSatisfy(result -> assertThat(result)
                            .hasStatusOk()
                            .bodyJson()
                            .extractingPath("$.status")
                            .isEqualTo("deactivated"));
            assertThat(auditFor(team.tenant(), team.staffMembership()))
                    .as("round " + round)
                    .containsExactly("member.deactivated|{\"to\": \"deactivated\", \"from\": \"active\"}");
        }
    }

    @Test
    void P13_deactivatedMemberIsRefusedOnNextRequest() throws Exception {
        Team team = team();
        String staffToken = tokens.forUser(team.staffUser()); // stays valid throughout
        String profile = "{\"fullName\": \"Ravi\", \"phone\": null}";
        assertThat(put(staffToken, "/api/v1/me/profile", profile, "p13")).hasStatusOk();

        assertThat(post(team.adminUser(), "/api/v1/members/" + team.staffMembership() + "/deactivate"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("deactivated");

        MvcTestResult refused = put(staffToken, "/api/v1/me/profile", profile, "p13");
        MvcTestResult neverAMember = put(tokens.forUser(UUID.randomUUID()), "/api/v1/me/profile", profile, "p13");
        assertThat(refused).hasStatus(403).bodyJson().extractingPath("$.code").isEqualTo("no_active_membership");
        assertThat(body(refused)).isEqualTo(body(neverAMember));
        assertThat(get(team.staffUser(), "/api/v1/me"))
                .bodyJson()
                .extractingPath("$.business")
                .isNull();
    }

    @Test
    void reactivationRestoresAccess() {
        Team team = team();
        post(team.adminUser(), "/api/v1/members/" + team.staffMembership() + "/deactivate");

        assertThat(post(team.adminUser(), "/api/v1/members/" + team.staffMembership() + "/reactivate"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("active");
        assertThat(get(team.staffUser(), "/api/v1/me"))
                .bodyJson()
                .extractingPath("$.role")
                .isEqualTo("staff");
        assertThat(audit(team.tenant()))
                .containsExactly(
                        "member.deactivated|%s|memberships|%s|{\"to\": \"deactivated\", \"from\": \"active\"}"
                                .formatted(team.adminUser(), team.staffMembership()),
                        "member.reactivated|%s|memberships|%s|{\"to\": \"active\", \"from\": \"deactivated\"}"
                                .formatted(team.adminUser(), team.staffMembership()));
    }

    @Test
    void reactivatingSomeoneActiveInAnotherBusinessIs409AlreadyHasBusiness() {
        Team team = team();
        post(team.adminUser(), "/api/v1/members/" + team.staffMembership() + "/deactivate");
        data.membership(data.tenant(), team.staffUser(), "admin", "active"); // now active elsewhere (D4)

        assertThat(post(team.adminUser(), "/api/v1/members/" + team.staffMembership() + "/reactivate"))
                .hasStatus(409)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("already_has_business");
        assertThat(status(team.staffMembership())).isEqualTo("staff,deactivated");
    }

    @Test
    void otherStatusChangesAre409InvalidStatusChange() {
        Team team = team();
        UUID invited = data.membership(team.tenant(), data.user(), "staff", "invited");

        for (String action : List.of("deactivate", "reactivate")) {
            assertThat(post(team.adminUser(), "/api/v1/members/" + invited + "/" + action))
                    .as(action)
                    .hasStatus(409)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("invalid_status_change");
        }
        assertThat(post(team.adminUser(), "/api/v1/members/" + team.staffMembership() + "/reactivate"))
                .hasStatusOk(); // already active: a no-op
    }

    @Test
    void badInputIs400() {
        Team team = team();
        assertThat(patch(
                        team.adminUser(),
                        "/api/v1/members/" + team.staffMembership() + "/role",
                        "{\"role\": \"owner\"}"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("validation_failed");
        assertThat(patch(
                        team.adminUser(),
                        "/api/v1/members/" + team.staffMembership() + "/role",
                        "{\"role\": \"admin\", \"status\": \"active\"}"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed_request");
        assertThat(post(team.adminUser(), "/api/v1/members/not-a-uuid/deactivate"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed_request");
    }

    private void assertLastAdmin(MvcTestResult result) {
        assertThat(result).hasStatus(409).bodyJson().isLenientlyEqualTo("""
                        {"code": "last_admin", "message": "A business must keep at least one active admin"}""");
    }

    /** Exactly one request succeeded; the other was refused, either by the trigger or because it was no longer admin. */
    private static void assertOneWonAndTheOtherWasRefused(List<MvcTestResult> results) throws Exception {
        List<Integer> statuses =
                results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).containsOnlyOnce(200);
        MvcTestResult loser = results.get(statuses.indexOf(200) == 0 ? 1 : 0);
        assertThat(loser.getResponse().getStatus()).isIn(403, 409);
        assertThat(body(loser)).containsAnyOf("\"code\":\"last_admin\"", "\"code\":\"admin_only\"");
    }

    @SafeVarargs
    private static List<MvcTestResult> together(Callable<MvcTestResult>... requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<MvcTestResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests.length)) {
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (Callable<MvcTestResult> request : requests) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            for (Future<MvcTestResult> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private MvcTestResult memberAction(UUID user, UUID membership, String action) {
        String path = "/api/v1/members/" + membership + "/" + action;
        return action.equals("role")
                ? mvc.patch()
                        .uri(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                        .header(LogContext.REQUEST_ID_HEADER, "p9-member")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"admin\"}")
                        .exchange()
                : mvc.post()
                        .uri(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                        .header(LogContext.REQUEST_ID_HEADER, "p9-member")
                        .exchange();
    }

    private MvcTestResult get(UUID user, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .exchange();
    }

    private MvcTestResult post(UUID user, String uri) {
        return mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .exchange();
    }

    private MvcTestResult patch(UUID user, String uri, String json) {
        return mvc.patch()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult put(String token, String uri, String json, String requestId) {
        return mvc.put()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(LogContext.REQUEST_ID_HEADER, requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private void setName(UUID user, String name) {
        admin.sql("update northstar.user_profiles set full_name = :name where id = :id")
                .param("name", name)
                .param("id", user)
                .update();
    }

    private String status(UUID membership) {
        return admin.sql("select role || ',' || status from northstar.memberships where id = :id")
                .param("id", membership)
                .query(String.class)
                .single();
    }

    private int activeAdmins(UUID tenant) {
        return admin.sql("""
                        select count(*) from northstar.memberships
                        where tenant_id = :tenant and role = 'admin' and status = 'active'""").param("tenant", tenant).query(Integer.class).single();
    }

    /** The tenant's member audit entries, oldest first: action|actor|table|target|metadata. */
    private List<String> audit(UUID tenant) {
        return admin.sql("""
                        select action || '|' || actor_user_id || '|' || target_table || '|' || target_id || '|'
                               || coalesce(metadata::text, '')
                        from northstar.audit_log
                        where tenant_id = :tenant and action like 'member.%'
                        order by created_at, id""").param("tenant", tenant).query(String.class).list();
    }

    /** One member's audit entries: action|metadata. */
    private List<String> auditFor(UUID tenant, UUID membership) {
        return admin.sql("""
                        select action || '|' || coalesce(metadata::text, '')
                        from northstar.audit_log
                        where tenant_id = :tenant and target_id = :membership""")
                .param("tenant", tenant)
                .param("membership", membership)
                .query(String.class)
                .list();
    }

    private static String body(MvcTestResult result) throws UnsupportedEncodingException {
        return result.getResponse().getContentAsString();
    }
}
