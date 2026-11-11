package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.fakes.FakeEmailSender;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Staff invites (step 1.9) through the real login path: create, list, revoke, resend, accept, and P12. Since step 1.12
 * the link arrives only by email: each invite's email action is run as the worker runs it, and the token is read from
 * the fake email sender's outbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InvitesTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final JdbcClient admin;
    private final DataSource dataSource;
    private final FakeEmailSender email;
    private final ExecuteActionHandler executeAction;

    InvitesTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired PostgresAdmin postgres,
            @Autowired DataSource dataSource,
            @Autowired FakeEmailSender email,
            @Autowired ExecuteActionHandler executeAction) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
        this.dataSource = dataSource;
        this.email = email;
        this.executeAction = executeAction;
    }

    /** A business with an admin and a staff member. */
    private record Team(UUID tenant, UUID adminUser, UUID staffUser) {}

    private Team team() {
        TestData.Member owner = data.member("admin");
        UUID staff = data.user();
        data.membership(owner.tenantId(), staff, "staff", "active");
        return new Team(owner.tenantId(), owner.userId(), staff);
    }

    /** An invite created through the API, its email sent, with the raw token taken from the emailed link. */
    private record Sent(UUID id, String token, String email) {}

    private Sent invite(Team team, String role) throws Exception {
        String email = "invitee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MvcTestResult created = createInvite(team.adminUser(), email, role);
        assertThat(created).hasStatus(201);
        return delivered(created, email);
    }

    @Test
    void anAdminInvitesAndTheInviteeJoinsWithTheInvitesRole() throws Exception {
        Team team = team();
        String email = "Asha.Rao." + UUID.randomUUID().toString().substring(0, 6) + "@Example.com";

        MvcTestResult created = createInvite(team.adminUser(), email, "staff");
        assertThat(created).hasStatus(201).bodyJson().isLenientlyEqualTo("""
                        {"email": "%s", "role": "staff", "status": "pending"}""".formatted(email));
        assertThat(body(created)).as("the link goes only by email").doesNotContain("inviteLink", "token");
        Sent sent = delivered(created, email);
        assertThat(sent.token()).matches(InviteTokens.FORMAT);
        assertThat(admin.sql(
                                "select token_hash || ',' || (expires_at - created_at) from northstar.invites where id = :id")
                        .param("id", sent.id())
                        .query(String.class)
                        .single())
                .isEqualTo(InviteTokens.hash(sent.token()) + ",7 days");

        UUID invitee = UUID.randomUUID(); // signed up, no profile and no membership yet
        assertThat(accept(invitee, email.toLowerCase(), sent.token())) // the email match ignores case
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"business": {"id": "%s"}, "role": "staff"}""".formatted(team.tenant()));

        assertThat(membershipOf(invitee, team.tenant())).isEqualTo("staff,active");
        assertThat(inviteState(sent.id())).isEqualTo("accepted");
        assertThat(inviteAudit(team.tenant()))
                .containsExactly("invite.created|" + sent.id() + "|", "invite.accepted|" + sent.id() + "|");
        assertThat(get(invitee, "/api/v1/me"))
                .bodyJson()
                .extractingPath("$.role")
                .isEqualTo("staff");
    }

    @Test
    void P12_inviteTokenIsSingleUse() throws Exception {
        Team team = team();
        Sent sent = invite(team, "staff");
        UUID invitee = UUID.randomUUID();
        assertThat(accept(invitee, sent.email(), sent.token())).hasStatusOk();

        assertRefused(accept(invitee, sent.email(), sent.token()), 410, "invite_used");
        assertRefused(accept(UUID.randomUUID(), sent.email(), sent.token()), 410, "invite_used");
    }

    @Test
    void P12_concurrentAcceptsMakeOneMembership() throws Exception {
        for (int round = 0; round < 5; round++) {
            Team team = team();
            Sent sent = invite(team, "staff");
            UUID invitee = UUID.randomUUID();

            List<MvcTestResult> results = together(
                    () -> accept(invitee, sent.email(), sent.token()),
                    () -> accept(invitee, sent.email(), sent.token()));

            List<Integer> statuses =
                    results.stream().map(r -> r.getResponse().getStatus()).toList();
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 410);
            assertThat(countMemberships(invitee)).isOne();
            assertThat(inviteAudit(team.tenant()))
                    .filteredOn(entry -> entry.startsWith("invite.accepted"))
                    .hasSize(1);
        }
    }

    @Test
    void P12_expiredInviteIsRefused() throws Exception {
        Team team = team();
        Sent sent = invite(team, "staff");
        expire(sent.id());

        assertRefused(accept(UUID.randomUUID(), sent.email(), sent.token()), 410, "invite_expired");
        assertThat(inviteState(sent.id())).isEqualTo("open");
        assertThat(list(team.adminUser()))
                .bodyJson()
                .extractingPath("$[0].status")
                .isEqualTo("expired");
    }

    @Test
    void P12_revokedInviteIsRefused() throws Exception {
        Team team = team();
        Sent sent = invite(team, "staff");
        assertThat(post(team.adminUser(), "/api/v1/invites/" + sent.id() + "/revoke"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("revoked");

        UUID invitee = UUID.randomUUID();
        assertRefused(accept(invitee, sent.email(), sent.token()), 410, "invite_revoked");
        assertThat(countMemberships(invitee)).isZero();
    }

    @Test
    void P12_resentInviteKillsTheOldLink() throws Exception {
        Team team = team();
        Sent first = invite(team, "admin");

        MvcTestResult resent = post(team.adminUser(), "/api/v1/invites/" + first.id() + "/resend");
        assertThat(resent).hasStatus(201).bodyJson().extractingPath("$.role").isEqualTo("admin");
        Sent second = delivered(resent, first.email());
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.token()).isNotEqualTo(first.token());

        UUID invitee = UUID.randomUUID();
        assertRefused(accept(invitee, first.email(), first.token()), 410, "invite_revoked");
        assertThat(accept(invitee, first.email(), second.token())).hasStatusOk();
        assertThat(inviteAudit(team.tenant()))
                .containsExactly(
                        "invite.created|" + first.id() + "|",
                        "invite.resent|" + second.id() + "|",
                        "invite.accepted|" + second.id() + "|");
    }

    @Test
    void P12_revokeRacingAcceptLeavesAConsistentResult() throws Exception {
        for (int round = 0; round < 5; round++) {
            Team team = team();
            Sent sent = invite(team, "staff");
            UUID invitee = UUID.randomUUID();

            List<MvcTestResult> results = together(
                    () -> post(team.adminUser(), "/api/v1/invites/" + sent.id() + "/revoke"),
                    () -> accept(invitee, sent.email(), sent.token()));
            MvcTestResult revoke = results.get(0);
            MvcTestResult accept = results.get(1);

            if (accept.getResponse().getStatus() == 200) {
                assertRefused(revoke, 409, "invite_already_accepted");
                assertThat(inviteState(sent.id())).isEqualTo("accepted");
                assertThat(countMemberships(invitee)).isOne();
            } else {
                assertThat(revoke).hasStatusOk();
                assertRefused(accept, 410, "invite_revoked");
                assertThat(inviteState(sent.id())).isEqualTo("revoked");
                assertThat(countMemberships(invitee)).isZero();
            }
        }
    }

    @Test
    void aWrongAccountLearnsNothingAboutTheInvite() throws Exception {
        Team team = team();
        Sent sent = invite(team, "staff");
        String businessName = admin.sql("select name from northstar.tenants where id = :id")
                .param("id", team.tenant())
                .query(String.class)
                .single();

        MvcTestResult wrongEmail = accept(UUID.randomUUID(), "someone-else@example.com", sent.token());
        assertRefused(wrongEmail, 403, "invite_email_mismatch");
        assertThat(body(wrongEmail)).doesNotContain(sent.email(), team.tenant().toString(), businessName);

        // Checked before the invite's state: a wrong account on an expired invite still gets 403, not 410.
        expire(sent.id());
        assertRefused(
                accept(UUID.randomUUID(), "someone-else@example.com", sent.token()), 403, "invite_email_mismatch");
        assertThat(inviteState(sent.id())).isEqualTo("open");
    }

    @Test
    void acceptingWhileActiveInAnotherBusinessIs409AndChangesNothing() throws Exception {
        Team team = team();
        Sent sent = invite(team, "staff");
        TestData.Member elsewhere = data.member("staff");

        assertRefused(accept(elsewhere.userId(), sent.email(), sent.token()), 409, "already_has_business");
        assertThat(inviteState(sent.id())).isEqualTo("open");
        assertThat(membershipOf(elsewhere.userId(), team.tenant())).isNull();
    }

    @Test
    void acceptingTwoBusinessesAtOnceJoinsOnlyOne() throws Exception {
        for (int round = 0; round < 3; round++) {
            Sent first = invite(team(), "staff");
            Team other = team();
            UUID invitee = UUID.randomUUID();
            String email = first.email();
            MvcTestResult second = createInvite(other.adminUser(), email, "staff");
            Sent sentByOther = delivered(second, email);

            List<MvcTestResult> results = together(
                    () -> accept(invitee, email, first.token()), () -> accept(invitee, email, sentByOther.token()));

            List<Integer> statuses =
                    results.stream().map(r -> r.getResponse().getStatus()).toList();
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(admin.sql("select count(*) from northstar.memberships where user_id = :u and status = 'active'")
                            .param("u", invitee)
                            .query(Integer.class)
                            .single())
                    .isOne();
        }
    }

    @Test
    void reInvitingADeactivatedMemberReactivatesTheirMembership() throws Exception {
        Team team = team();
        UUID former = data.user();
        UUID formerMembership = data.membership(team.tenant(), former, "staff", "deactivated");
        Sent sent = invite(team, "admin");

        assertThat(accept(former, sent.email(), sent.token()))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.role")
                .isEqualTo("admin");

        assertThat(admin.sql("select id || ',' || role || ',' || status from northstar.memberships where user_id = :u")
                        .param("u", former)
                        .query(String.class)
                        .list())
                .containsExactly(formerMembership + ",admin,active");
        assertThat(inviteAudit(team.tenant()))
                .contains("invite.accepted|" + sent.id() + "|{\"to\": \"active\", \"from\": \"deactivated\"}");
    }

    @Test
    void staffGet403OnEveryAdminEndpoint() throws Exception {
        Team team = team();
        Sent sent = invite(team, "staff");
        UUID foreign = invite(team(), "staff").id();

        assertRefused(createInvite(team.staffUser(), "x@example.com", "staff"), 403, "admin_only");
        assertRefused(list(team.staffUser()), 403, "admin_only");
        for (UUID target : List.of(sent.id(), foreign, UUID.randomUUID())) {
            assertRefused(post(team.staffUser(), "/api/v1/invites/" + target + "/revoke"), 403, "admin_only");
            assertRefused(post(team.staffUser(), "/api/v1/invites/" + target + "/resend"), 403, "admin_only");
        }
        assertThat(inviteState(sent.id())).isEqualTo("open");
    }

    @Test
    void P9_inviteFromAnotherTenantIs404() throws Exception {
        Team team = team();
        UUID foreign = invite(team(), "staff").id();
        UUID missing = UUID.randomUUID();

        for (String action : List.of("revoke", "resend")) {
            MvcTestResult other = postWithRequestId(team.adminUser(), "/api/v1/invites/" + foreign + "/" + action);
            MvcTestResult none = postWithRequestId(team.adminUser(), "/api/v1/invites/" + missing + "/" + action);
            assertThat(other).as(action).hasStatus(404);
            assertThat(body(other)).as(action).isEqualTo(body(none));
        }
        assertThat(inviteState(foreign)).isEqualTo("open");
    }

    @Test
    void onlyOnePendingInvitePerEmailIgnoringCase() throws Exception {
        Team team = team();
        Sent first = invite(team, "staff");

        MvcTestResult again = createInvite(team.adminUser(), first.email().toUpperCase(), "admin");
        assertThat(again).hasStatus(409).bodyJson().isLenientlyEqualTo("""
                        {"code": "invite_pending", "details": {"inviteId": "%s"}}""".formatted(first.id()));

        post(team.adminUser(), "/api/v1/invites/" + first.id() + "/revoke");
        assertThat(createInvite(team.adminUser(), first.email(), "admin")).hasStatus(201);
    }

    @Test
    void revokeAndResendRulesAndIdempotency() throws Exception {
        Team team = team();
        Sent revoked = invite(team, "staff");
        assertThat(post(team.adminUser(), "/api/v1/invites/" + revoked.id() + "/revoke"))
                .hasStatusOk();
        assertThat(post(team.adminUser(), "/api/v1/invites/" + revoked.id() + "/revoke"))
                .hasStatusOk();
        assertRefused(
                post(team.adminUser(), "/api/v1/invites/" + revoked.id() + "/resend"), 409, "invite_already_revoked");

        Sent accepted = invite(team, "staff");
        accept(UUID.randomUUID(), accepted.email(), accepted.token());
        assertRefused(
                post(team.adminUser(), "/api/v1/invites/" + accepted.id() + "/revoke"), 409, "invite_already_accepted");
        assertRefused(
                post(team.adminUser(), "/api/v1/invites/" + accepted.id() + "/resend"), 409, "invite_already_accepted");

        Sent expired = invite(team, "staff");
        expire(expired.id());
        assertThat(post(team.adminUser(), "/api/v1/invites/" + expired.id() + "/resend"))
                .hasStatus(201);

        assertThat(inviteAudit(team.tenant()))
                .filteredOn(entry -> entry.startsWith("invite.revoked"))
                .containsExactly("invite.revoked|" + revoked.id() + "|{\"to\": \"revoked\", \"from\": \"pending\"}");
    }

    @Test
    void unknownOrMalformedTokensAreRefused() {
        assertRefused(accept(UUID.randomUUID(), "x@example.com", InviteTokens.newToken()), 404, "invite_not_found");
        assertThat(accept(UUID.randomUUID(), "x@example.com", "short"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("validation_failed");
    }

    @Test
    void theTokenAndEmailNeverReachLogsListsOrTheAuditLog(CapturedOutput output) throws Exception {
        Team team = team();
        Sent sent = invite(team, "staff");
        Sent revoked = invite(team, "staff");
        post(team.adminUser(), "/api/v1/invites/" + revoked.id() + "/resend");
        accept(UUID.randomUUID(), "someone-else@example.com", sent.token()); // a refused attempt is not logged either
        accept(UUID.randomUUID(), sent.email(), sent.token());

        String listed = body(list(team.adminUser()));
        String auditRows = String.join(
                "\n",
                admin.sql("""
                        select coalesce(metadata::text, '') || action from northstar.audit_log where tenant_id = :t""").param("t", team.tenant()).query(String.class).list());

        for (String secret : List.of(sent.token(), InviteTokens.hash(sent.token()), revoked.token())) {
            assertThat(listed).doesNotContain(secret);
            assertThat(auditRows).doesNotContain(secret);
            assertThat(output.getAll()).doesNotContain(secret);
        }
        assertThat(listed).doesNotContain("inviteLink", "token");
        assertThat(auditRows).doesNotContain(sent.email(), revoked.email());
        assertThat(output.getAll()).doesNotContain(sent.email(), revoked.email());
    }

    @Test
    void acceptingIsRateLimitedPerUser() {
        UUID user = UUID.randomUUID();
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(accept(user, "x@example.com", InviteTokens.newToken()))
                    .as("attempt " + attempt)
                    .hasStatus(404);
        }
        assertRefused(accept(user, "x@example.com", InviteTokens.newToken()), 429, "rate_limited");
        assertThat(accept(UUID.randomUUID(), "x@example.com", InviteTokens.newToken()))
                .hasStatus(404);
    }

    @Test
    void theAcceptanceFunctionIsLockedDown() {
        assertThat(admin.sql("""
                        select pg_get_userbyid(p.proowner) || ',' || p.prosecdef || ',' || array_to_string(p.proconfig, ';')
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'accept_invite'""").query(String.class).single())
                .isEqualTo("migrator,true,search_path=pg_catalog, pg_temp");
        assertThat(admin.sql("""
                        select string_agg(r, ',' order by r)
                        from unnest(array['app_user', 'app_admin', 'public', 'anon', 'authenticated']) r
                        where has_function_privilege(r, 'northstar.accept_invite(text, text)', 'EXECUTE')""").query(String.class).single()).isEqualTo("app_user");
    }

    @Test
    void acceptanceModeAllowsOnlyTheAcceptance() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.unwrap(TenantGuardedConnection.class).beginInviteAcceptance(UUID.randomUUID());

            assertThatThrownBy(() -> connection.prepareStatement("select * from northstar.invites"))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.MEMBERSHIP_LOOKUP))
                    .isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(connection::createStatement).isInstanceOf(MissingTenantContextException.class);
            assertThatThrownBy(() -> connection.prepareCall(TenantGuardDataSource.INVITE_ACCEPTANCE))
                    .isInstanceOf(MissingTenantContextException.class);

            connection.prepareStatement(TenantGuardDataSource.INVITE_ACCEPTANCE).close();
            connection.rollback();

            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.INVITE_ACCEPTANCE))
                    .isInstanceOf(MissingTenantContextException.class);
            connection.unwrap(TenantGuardedConnection.class).beginMembershipLookup(UUID.randomUUID());
            assertThatThrownBy(() -> connection.prepareStatement(TenantGuardDataSource.INVITE_ACCEPTANCE))
                    .isInstanceOf(MissingTenantContextException.class);
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    // Requests.

    private MvcTestResult createInvite(UUID adminUser, String email, String role) {
        return mvc.post()
                .uri("/api/v1/invites")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(adminUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"role\": \"%s\"}".formatted(email, role))
                .exchange();
    }

    private MvcTestResult accept(UUID user, String email, String token) {
        return mvc.post()
                .uri("/api/v1/invites/accept")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokens.token(user).claim("email", email).sign())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\"}".formatted(token))
                .exchange();
    }

    private MvcTestResult list(UUID user) {
        return get(user, "/api/v1/invites");
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

    private MvcTestResult postWithRequestId(UUID user, String uri) {
        return mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, "p9-invite")
                .exchange();
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

    // Assertions and database checks.

    private static void assertRefused(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).bodyJson().extractingPath("$.code").isEqualTo(code);
    }

    /**
     * The invite from a create or resend response, after its email action has run as the worker runs it (on a thread
     * that serves no request), with the token taken from the emailed link.
     */
    private Sent delivered(MvcTestResult result, String address) throws Exception {
        UUID id = UUID.fromString(body(result).replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        Map<String, Object> action = admin.sql(
                        "select id, tenant_id from northstar.actions where idempotency_key = :key")
                .param("key", "invite-email:" + id)
                .query()
                .singleRow();
        ExecuteAction job = new ExecuteAction((UUID) action.get("tenant_id"), (UUID) action.get("id"));
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> executeAction.run(job)).get(30, TimeUnit.SECONDS);
        }
        String token = email.latestTokenFor(address).orElseThrow(() -> new AssertionError("no invite email"));
        return new Sent(id, token, address);
    }

    private void expire(UUID invite) {
        admin.sql("update northstar.invites set expires_at = now() - interval '1 minute' where id = :id")
                .param("id", invite)
                .update();
    }

    private String inviteState(UUID invite) {
        return admin.sql("""
                        select case when accepted_at is not null then 'accepted'
                                    when revoked_at is not null then 'revoked' else 'open' end
                        from northstar.invites where id = :id""").param("id", invite).query(String.class).single();
    }

    private String membershipOf(UUID user, UUID tenant) {
        return admin.sql(
                        "select role || ',' || status from northstar.memberships where user_id = :u and tenant_id = :t")
                .param("u", user)
                .param("t", tenant)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private int countMemberships(UUID user) {
        return admin.sql("select count(*) from northstar.memberships where user_id = :u")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    /** The tenant's invite audit entries, oldest first: action|target|metadata. */
    private List<String> inviteAudit(UUID tenant) {
        return admin.sql("""
                        select action || '|' || target_id || '|' || coalesce(metadata::text, '')
                        from northstar.audit_log
                        where tenant_id = :t and action like 'invite.%'
                        order by created_at, id""").param("t", tenant).query(String.class).list();
    }

    private static String body(MvcTestResult result) throws UnsupportedEncodingException {
        return result.getResponse().getContentAsString();
    }
}
