package com.northstar.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.FakeEmailSender;
import com.northstar.tenancy.TenantContext;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * The invite email as an action (step 1.12, P12): created with the invite, sent once by the worker with a link that
 * works, the token set at send time through northstar.set_invite_token(), and never stored, logged or returned.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class InviteEmailTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final JdbcClient admin;
    private final AppUserDatabase app;
    private final FakeEmailSender email;
    private final ExecuteActionHandler executeAction;

    InviteEmailTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired PostgresAdmin postgres,
            @Autowired AppUserDatabase app,
            @Autowired FakeEmailSender email,
            @Autowired ExecuteActionHandler executeAction) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
        this.app = app;
        this.email = email;
        this.executeAction = executeAction;
    }

    @Test
    void creatingAnInviteQueuesOneEmailActionAndReturnsNoLink() throws Exception {
        TestData.Member owner = data.member("admin");
        String address = address();

        MvcTestResult created = createInvite(owner, address, "staff");

        assertThat(created).hasStatus(201);
        assertThat(body(created)).doesNotContain("inviteLink", "token", "#token=");
        UUID invite = inviteId(created);
        assertThat(admin.sql("""
                        select type || '|' || status || '|' || mode || '|' || payload::text
                        from northstar.actions where tenant_id = :t""")
                        .param("t", owner.tenantId())
                        .query(String.class)
                        .list())
                .containsExactly("invite.email|pending|automatic|{\"inviteId\": \"" + invite + "\"}");
        assertThat(email.sentTo(address))
                .as("nothing is sent until the worker runs the action")
                .isEmpty();
    }

    @Test
    void theEmailIsSentOnceWithTheConfirmedWordingAndAWorkingLink() throws Exception {
        TestData.Member owner = data.member("admin");
        String address = address();
        UUID invite = inviteId(createInvite(owner, address, "admin"));

        runEmailAction(invite);
        runEmailAction(invite); // the job delivered again: the action already succeeded

        List<FakeEmailSender.Sent> sent = email.sentTo(address);
        assertThat(sent).hasSize(1);
        String token = email.latestTokenFor(address).orElseThrow();
        Instant expiresAt = admin.sql("select expires_at from northstar.invites where id = :id")
                .param("id", invite)
                .query(Instant.class)
                .single();
        String expiry = DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a z", Locale.ENGLISH)
                .format(expiresAt.atZone(ZoneId.of("Asia/Kolkata")));
        assertThat(expiry).matches("\\d{1,2} [A-Z][a-z]+ \\d{4}, \\d{1,2}:\\d{2} (AM|PM) IST");
        assertThat(sent.getFirst().subject()).isEqualTo("Test business invited you to Northstar");
        assertThat(sent.getFirst().text()).isEqualTo("""
                Hi,

                Test business has invited you to join their team on Northstar as Admin.

                Accept your invite: http://localhost:5173/invite#token=%s

                This link works once and expires on %s.
                If you weren't expecting this, you can ignore this email.

                — Northstar
                """.formatted(token, expiry));
        assertThat(actionState(invite)).isEqualTo("succeeded|1");
        assertThat(accept(UUID.randomUUID(), address, token)).hasStatusOk();
    }

    @Test
    void theExpiryIsShownInTheBusinessesTimezone() throws Exception {
        TestData.Member owner = data.member("admin");
        admin.sql("update northstar.tenants set timezone = 'America/New_York' where id = :t")
                .param("t", owner.tenantId())
                .update();
        String address = address();
        UUID invite = inviteId(createInvite(owner, address, "staff"));

        runEmailAction(invite);

        assertThat(email.sentTo(address).getFirst().text())
                .contains("as Staff.")
                .containsPattern("expires on \\d{1,2} [A-Z][a-z]+ \\d{4}, \\d{1,2}:\\d{2} (AM|PM) E[SD]T\\.");
    }

    @Test
    void P12_resendingKillsTheOldLinkAndTheOldEmailSendsNothing() throws Exception {
        TestData.Member owner = data.member("admin");
        String address = address();
        UUID first = inviteId(createInvite(owner, address, "staff"));
        runEmailAction(first);
        String oldToken = email.latestTokenFor(address).orElseThrow();

        UUID second = inviteId(post(owner, "/api/v1/invites/" + first + "/resend"));
        runEmailAction(second);
        String newToken = email.latestTokenFor(address).orElseThrow();

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(email.sentTo(address)).hasSize(2);
        assertThat(accept(UUID.randomUUID(), address, oldToken))
                .hasStatus(410)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("invite_revoked");
        assertThat(accept(UUID.randomUUID(), address, newToken)).hasStatusOk();

        // An email still queued for a resent invite finds it revoked and sends nothing.
        String other = address();
        UUID queued = inviteId(createInvite(owner, other, "staff"));
        UUID replacement = inviteId(post(owner, "/api/v1/invites/" + queued + "/resend"));
        runEmailAction(queued);
        assertThat(actionResult(queued)).isEqualTo("{\"sent\": false}");
        assertThat(email.sentTo(other)).isEmpty();
        runEmailAction(replacement);
        assertThat(email.sentTo(other)).hasSize(1);
    }

    @Test
    void aRevokedInviteGetsNoEmail() throws Exception {
        TestData.Member owner = data.member("admin");
        String address = address();
        UUID invite = inviteId(createInvite(owner, address, "staff"));
        post(owner, "/api/v1/invites/" + invite + "/revoke");

        runEmailAction(invite);

        assertThat(actionState(invite)).isEqualTo("succeeded|1");
        assertThat(actionResult(invite)).isEqualTo("{\"sent\": false}");
        assertThat(email.sentTo(address)).isEmpty();
    }

    /**
     * The decision for step 1.12: a sent email whose response was lost is retried with the same derived token, so the
     * body is identical under the same idempotency key, Resend sends nothing new, and the one email's link works.
     */
    @Test
    void aLostResponseRetriedSendsOneEmailWhoseLinkWorks() throws Exception {
        TestData.Member owner = data.member("admin");
        String address = address();
        UUID invite = inviteId(createInvite(owner, address, "staff"));
        email.provider().script(key(invite), FakeCall.performedButLost());

        runEmailAction(invite);
        assertThat(actionState(invite)).isEqualTo("pending|1");
        makeDue(invite);
        runEmailAction(invite);

        assertThat(actionState(invite)).isEqualTo("succeeded|2");
        assertThat(email.provider().calls(key(invite))).isEqualTo(2);
        assertThat(email.sentTo(address)).as("Resend deduplicated the retry").hasSize(1);
        assertThat(accept(
                        UUID.randomUUID(),
                        address,
                        email.latestTokenFor(address).orElseThrow()))
                .hasStatusOk();
    }

    @Test
    void pastResendsIdempotencyWindowTheRetrySendsTheSameWorkingLinkAgain() throws Exception {
        TestData.Member owner = data.member("admin");
        String address = address();
        UUID invite = inviteId(createInvite(owner, address, "staff"));
        email.provider().script(key(invite), FakeCall.performedButLost());

        runEmailAction(invite);
        email.expireIdempotencyKey(key(invite)); // as if the retry came more than 24 hours later
        makeDue(invite);
        runEmailAction(invite);

        List<FakeEmailSender.Sent> sent = email.sentTo(address);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).text())
                .as("the same link both times")
                .isEqualTo(sent.get(0).text());
        assertThat(accept(
                        UUID.randomUUID(),
                        address,
                        email.latestTokenFor(address).orElseThrow()))
                .hasStatusOk();
    }

    @Test
    void theTokenNeverAppearsInLogsTheDatabaseOrTheAuditLog(CapturedOutput output) throws Exception {
        TestData.Member owner = data.member("admin");
        String address = address();
        UUID invite = inviteId(createInvite(owner, address, "staff"));
        runEmailAction(invite);
        String token = email.latestTokenFor(address).orElseThrow();
        assertThat(accept(UUID.randomUUID(), address, token)).hasStatusOk();

        String stored = String.join(
                "\n",
                admin.sql("""
                        select row_to_json(i)::text from northstar.invites i where i.tenant_id = :t
                        union all select row_to_json(a)::text from northstar.actions a where a.tenant_id = :t
                        union all select row_to_json(l)::text from northstar.audit_log l where l.tenant_id = :t
                        union all select row_to_json(j)::text from jobrunr.jobrunr_jobs j""").param("t", owner.tenantId()).query(String.class).list());

        assertThat(stored).contains(invite.toString()).doesNotContain(token);
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(address);
        assertThat(admin.sql(
                                "select payload::text || coalesce(result::text, '') from northstar.actions where tenant_id = :t")
                        .param("t", owner.tenantId())
                        .query(String.class)
                        .single())
                .doesNotContain(address)
                .doesNotContain(token);
    }

    @Test
    void setInviteTokenWorksOnlyForTheSystemOnAPendingInviteInItsTenant() {
        TestData.Member owner = data.member("admin");
        TestData.Member other = data.member("admin");
        UUID invite = insertInvite(owner, null);
        UUID revoked = insertInvite(owner, "now()");
        String hash = "a".repeat(64);

        assertThat(setToken(owner.tenantId(), owner.userId(), "admin", invite, hash))
                .as("an admin request cannot set a token")
                .isFalse();
        assertThat(setToken(other.tenantId(), TenantContext.SYSTEM_USER_ID, "system", invite, hash))
                .as("another tenant's invite")
                .isFalse();
        assertThat(setToken(owner.tenantId(), TenantContext.SYSTEM_USER_ID, "system", revoked, hash))
                .as("a revoked invite")
                .isFalse();
        assertThat(setToken(owner.tenantId(), TenantContext.SYSTEM_USER_ID, "system", invite, "not-a-hash"))
                .isFalse();
        assertThat(tokenHash(invite)).isEqualTo("throwaway-" + invite);

        assertThat(setToken(owner.tenantId(), TenantContext.SYSTEM_USER_ID, "system", invite, hash))
                .isTrue();
        assertThat(tokenHash(invite)).isEqualTo(hash);
    }

    @Test
    void setInviteTokenIsLockedDown() {
        assertThat(admin.sql("""
                        select pg_get_userbyid(p.proowner) || ',' || p.prosecdef || ',' || array_to_string(p.proconfig, ';')
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where n.nspname = 'northstar' and p.proname = 'set_invite_token'""").query(String.class).single())
                .isEqualTo("migrator,true,search_path=pg_catalog, pg_temp");
        assertThat(admin.sql("""
                        select string_agg(r, ',' order by r)
                        from unnest(array['app_user', 'app_admin', 'jobrunr_user', 'public', 'anon', 'authenticated']) r
                        where has_function_privilege(r, 'northstar.set_invite_token(uuid, text)', 'EXECUTE')""").query(String.class).single()).isEqualTo("app_user");
    }

    // Helpers.

    private MvcTestResult createInvite(TestData.Member admin, String address, String role) {
        return mvc.post()
                .uri("/api/v1/invites")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(admin.userId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"role\": \"%s\"}".formatted(address, role))
                .exchange();
    }

    private MvcTestResult post(TestData.Member admin, String uri) {
        return mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(admin.userId()))
                .exchange();
    }

    private MvcTestResult accept(UUID user, String address, String token) {
        return mvc.post()
                .uri("/api/v1/invites/accept")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokens.token(user).claim("email", address).sign())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\"}".formatted(token))
                .exchange();
    }

    /** Runs the invite's email action as the worker does: on a thread that serves no web request. */
    private void runEmailAction(UUID invite) throws Exception {
        Map<String, Object> action = admin.sql(
                        "select id, tenant_id from northstar.actions where idempotency_key = :key")
                .param("key", key(invite))
                .query()
                .singleRow();
        ExecuteAction job = new ExecuteAction((UUID) action.get("tenant_id"), (UUID) action.get("id"));
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> executeAction.run(job)).get(30, TimeUnit.SECONDS);
        }
    }

    private void makeDue(UUID invite) {
        admin.sql(
                        "update northstar.actions set next_attempt_at = now() - interval '1 second' where idempotency_key = :key")
                .param("key", key(invite))
                .update();
    }

    private String actionState(UUID invite) {
        return admin.sql("select status || '|' || attempts from northstar.actions where idempotency_key = :key")
                .param("key", key(invite))
                .query(String.class)
                .single();
    }

    private String actionResult(UUID invite) {
        return admin.sql("select result::text from northstar.actions where idempotency_key = :key")
                .param("key", key(invite))
                .query(String.class)
                .single();
    }

    private UUID insertInvite(TestData.Member owner, String revokedAt) {
        UUID id = UUID.randomUUID();
        admin.sql("""
                        insert into northstar.invites (id, tenant_id, email, role, token_hash, invited_by, revoked_at)
                        values (:id, :t, :email, 'staff', :hash, :by, %s)""".formatted(revokedAt == null ? "null" : revokedAt))
                .param("id", id)
                .param("t", owner.tenantId())
                .param("email", address())
                .param("hash", "throwaway-" + id)
                .param("by", owner.membershipId())
                .update();
        return id;
    }

    private boolean setToken(UUID tenant, UUID user, String role, UUID invite, String hash) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("select northstar.set_invite_token(:id, :hash)")
                        .param("id", invite)
                        .param("hash", hash)
                        .query(Boolean.class)
                        .single());
    }

    private String tokenHash(UUID invite) {
        return admin.sql("select token_hash from northstar.invites where id = :id")
                .param("id", invite)
                .query(String.class)
                .single();
    }

    private static String key(UUID invite) {
        return "invite-email:" + invite;
    }

    private static UUID inviteId(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(201);
        return UUID.fromString(body(result).replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
    }

    private static String address() {
        return "invitee-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
