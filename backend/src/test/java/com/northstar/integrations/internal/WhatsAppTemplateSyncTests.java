package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.contacts.MessageTemplates;
import com.northstar.contacts.TemplateStatus;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.FakeClock;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The template half of the WhatsApp sync (step 2.8b, integration-specs.md section 2, Template status from Meta) against
 * real Postgres with RLS, the worker's credential store and the fake reader's listings: statuses mapped (W34),
 * templates inserted with Meta's body and variables only, drafts adopted, names reused, paging and repeats, deletion
 * only after a complete listing, the time budget, ordering against newer webhooks, failures, P1, P9, P10 and logs.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, WhatsAppTemplateSyncTests.WorkerCredentials.class})
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppTemplateSyncTests {

    /** The worker's credential store, built by {@link CredentialsConfiguration} as the worker builds it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class WorkerCredentials {

        @Bean
        CredentialStore credentialStore(
                @Value("${northstar.credentials.keys}") String keys,
                @Value("${northstar.credentials.active-key}") String activeKey,
                @Value("${northstar.credentials.database.url}") String url,
                @Value("${northstar.credentials.database.username}") String username,
                @Value("${northstar.credentials.database.password}") String password) {
            return new CredentialsConfiguration().credentialStore(keys, activeKey, url, username, password, 2);
        }
    }

    private static final AtomicLong BLOCKS =
            new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000));
    private static final String BODY = "Hi {{1}}, your shoot is on {{2}}.";

    private final WhatsAppSyncRequests syncs;
    private final ExecuteActionHandler executeAction;
    private final FakeWhatsAppAccountReader meta;
    private final CredentialStore credentials;
    private final WhatsAppConnection connection;
    private final WhatsAppAccountHealth accounts;
    private final WhatsAppTemplateSync templateSync;
    private final MessageTemplates templates;
    private final ObjectProvider<CredentialStore> credentialProvider;
    private final FakeClock clock;
    private final PlatformTransactionManager transactionManager;
    private final TestData data;
    private final JdbcClient admin;

    WhatsAppTemplateSyncTests(
            @Autowired WhatsAppSyncRequests syncs,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeWhatsAppAccountReader meta,
            @Autowired CredentialStore credentials,
            @Autowired WhatsAppConnection connection,
            @Autowired WhatsAppAccountHealth accounts,
            @Autowired WhatsAppTemplateSync templateSync,
            @Autowired MessageTemplates templates,
            @Autowired ObjectProvider<CredentialStore> credentialProvider,
            @Autowired FakeClock clock,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.syncs = syncs;
        this.executeAction = executeAction;
        this.meta = meta;
        this.credentials = credentials;
        this.connection = connection;
        this.accounts = accounts;
        this.templateSync = templateSync;
        this.templates = templates;
        this.credentialProvider = credentialProvider;
        this.clock = clock;
        this.transactionManager = transactionManager;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @BeforeEach
    void runLikeAWorkerThread() {
        RequestContextHolder.resetRequestAttributes();
    }

    // Statuses (W34)

    @Test
    void W34_templateStatusUpdatedBySync() {
        Business business = business();
        UUID template = localTemplate(business.tenant(), "booking_confirmed", "1001", "submitted");

        meta.templates(business.waba(), List.of(listed("1001", "booking_confirmed", "APPROVED")));
        sync(business);
        assertThat(row(template)).startsWith("approved|UTILITY|1001|");
        String approvedAt = approvedAt(template);
        assertThat(approvedAt).isNotEmpty();

        meta.templates(business.waba(), List.of(listed("1001", "booking_confirmed", "PAUSED")));
        sync(business);
        assertThat(row(template)).startsWith("paused|");

        meta.templates(business.waba(), List.of(listed("1001", "booking_confirmed", "REJECTED")));
        sync(business);
        assertThat(row(template)).startsWith("rejected|");
        assertThat(approvedAt(template)).as("the first approval is kept").isEqualTo(approvedAt);
    }

    @Test
    void W34_everyStatusMetaListsBecomesOurs() {
        Business business = business();
        List<String[]> cases = List.of(
                new String[] {"PENDING", "submitted"},
                new String[] {"IN_APPEAL", "submitted"},
                new String[] {"APPROVED", "approved"},
                new String[] {"PAUSED", "paused"},
                new String[] {"REJECTED", "rejected"},
                new String[] {"LIMIT_EXCEEDED", "rejected"},
                new String[] {"DISABLED", "disabled"},
                new String[] {"PENDING_DELETION", "deleted"},
                new String[] {"DELETED", "deleted"},
                new String[] {"ARCHIVED", "archived"});
        List<WhatsAppAccountReader.ListedTemplate> listing = new java.util.ArrayList<>();
        for (int i = 0; i < cases.size(); i++) listing.add(listed("20" + i, "status_" + i, cases.get(i)[0]));
        meta.templates(business.waba(), listing);

        sync(business);

        for (int i = 0; i < cases.size(); i++) {
            assertThat(statusOf(business.tenant(), "status_" + i))
                    .as(cases.get(i)[0])
                    .isEqualTo(cases.get(i)[1]);
        }
    }

    // What is stored

    @Test
    void aTemplateNorthstarDoesNotHaveIsInsertedWithMetasBodyAndVariablesOnly() {
        Business business = business();
        meta.templates(
                business.waba(),
                List.of(
                        listed("3001", "booking_confirmed", "APPROVED"),
                        new WhatsAppAccountReader.ListedTemplate(
                                "3002",
                                "named_reminder",
                                "hi",
                                "PENDING",
                                "MARKETING",
                                "NAMED",
                                "Namaste {{first_name}}, {{shoot_type}} kal hai."),
                        new WhatsAppAccountReader.ListedTemplate(
                                "3003", "no_body", "en_US", "APPROVED", "AUTHENTICATION", null, null)));

        ActionView action = sync(business);

        UUID positional = idOf(business.tenant(), "booking_confirmed");
        assertThat(row(positional))
                .isEqualTo(
                        "approved|UTILITY|3001|" + BODY + "|{\"names\": [\"1\", \"2\"], \"format\": \"positional\"}");
        assertThat(approvedAt(positional)).isNotEmpty();
        assertThat(row(idOf(business.tenant(), "named_reminder")))
                .isEqualTo("submitted|MARKETING|3002|Namaste {{first_name}}, {{shoot_type}} kal hai."
                        + "|{\"names\": [\"first_name\", \"shoot_type\"], \"format\": \"named\"}");
        assertThat(row(idOf(business.tenant(), "no_body"))).isEqualTo("approved|AUTHENTICATION|3003||");
        assertThat(result(action.id()))
                .contains("\"templates\": 3", "\"templates_changed\": 3", "\"templates_complete\": true");
    }

    @Test
    void aDraftOfTheSameNameAndLanguageTakesMetasId() {
        Business business = business();
        UUID draft = localTemplate(business.tenant(), "booking_confirmed", null, "draft");
        meta.templates(business.waba(), List.of(listed("4001", "booking_confirmed", "PENDING")));

        sync(business);

        assertThat(row(draft)).startsWith("submitted|UTILITY|4001|" + BODY + "|");
        assertThat(count(business.tenant())).as("adopted, not a second row").isOne();
    }

    @Test
    void aNameMetaReusesMovesItsRowToTheNewTemplateAndTwoListedTemplatesOfOneNameKeepTheFirst(CapturedOutput output) {
        Business business = business();
        // Meta deleted 5001 and the name was used again for 5002: only 5002 is listed.
        UUID reused = localTemplate(business.tenant(), "booking_confirmed", "5001", "approved");
        // A second account of the business lists another template under a name the first already lists.
        String secondWaba = "waba-" + UUID.randomUUID();
        data.whatsAppNumber(business.tenant(), secondWaba);
        meta.templates(
                business.waba(),
                List.of(listed("5002", "booking_confirmed", "PENDING"), listed("5003", "shared", "APPROVED")));
        meta.templates(secondWaba, List.of(listed("5004", "shared", "REJECTED")));

        ActionView action = sync(business);

        assertThat(row(reused)).startsWith("submitted|UTILITY|5002|");
        UUID shared = idOf(business.tenant(), "shared");
        assertThat(row(shared)).as("the first listed keeps the row").startsWith("approved|UTILITY|5003|");
        assertThat(meta.pageReads(secondWaba)).hasSize(1);
        assertThat(output.getAll())
                .contains("whatsapp.sync.template_updated action_id=" + action.id() + " template_id=" + reused
                        + " replaced=true")
                .contains("whatsapp.sync.template_name_taken action_id=" + action.id() + " template_id=" + shared);
    }

    @Test
    void categoryChangesBySyncAreStoredAndLogged(CapturedOutput output) {
        Business business = business();
        UUID template = localTemplate(business.tenant(), "booking_confirmed", "6001", "approved");
        meta.templates(
                business.waba(),
                List.of(new WhatsAppAccountReader.ListedTemplate(
                        "6001", "booking_confirmed", "en_US", "APPROVED", "MARKETING", "POSITIONAL", BODY)));

        sync(business);

        assertThat(row(template)).startsWith("approved|MARKETING|6001|");
        assertThat(output.getAll())
                .contains("whatsapp.template.category_changed template_id=" + template
                        + " from=UTILITY to=MARKETING source=sync");
    }

    // The listing

    @Test
    void pagesAreFollowedByCursorAndATemplateListedTwiceIsAppliedOnceAsFirstListed() {
        Business business = business();
        meta.templates(
                business.waba(),
                List.of(listed("7001", "first", "APPROVED"), listed("7002", "second", "APPROVED")),
                List.of(listed("7002", "second", "REJECTED"), listed("7003", "third", "PAUSED")));

        sync(business);

        assertThat(meta.pageReads(business.waba()))
                .extracting(FakeWhatsAppAccountReader.PageRead::after)
                .containsExactly(null, "page-1");
        assertThat(statusOf(business.tenant(), "first")).isEqualTo("approved");
        assertThat(statusOf(business.tenant(), "second")).as("as first listed").isEqualTo("approved");
        assertThat(statusOf(business.tenant(), "third")).isEqualTo("paused");
    }

    @Test
    void aTemplateMissingFromACompleteListingIsDeleted(CapturedOutput output) {
        Business business = business();
        UUID gone = localTemplate(business.tenant(), "gone", "8001", "approved");
        UUID kept = localTemplate(business.tenant(), "kept", "8002", "approved");
        meta.templates(business.waba(), List.of(listed("8002", "kept", "APPROVED")));

        ActionView action = sync(business);

        assertThat(row(gone)).startsWith("deleted|");
        assertThat(row(kept)).startsWith("approved|");
        assertThat(output.getAll())
                .contains("whatsapp.sync.template_missing action_id=" + action.id() + " template_id=" + gone)
                .contains("whatsapp.template.status_changed template_id=" + gone
                        + " from=approved to=deleted source=sync");
        assertThat(result(action.id())).contains("\"templates_deleted\": 1");
    }

    @Test
    void aListingCutOffByTheTimeBudgetAppliesWhatItReadAndDeletesNothing(CapturedOutput output) {
        Business business = business();
        UUID missing = localTemplate(business.tenant(), "missing", "9001", "approved");
        meta.templates(
                business.waba(),
                List.of(listed("9002", "read_first", "APPROVED")),
                List.of(listed("9003", "never", "APPROVED")));
        meta.onTemplatePage(business.waba(), () -> clock.advance(Duration.ofMinutes(2)));
        WhatsAppSyncHandler budgeted = new WhatsAppSyncHandler(
                connection,
                accounts,
                credentialProvider,
                meta,
                templateSync,
                transactionManager,
                clock,
                Duration.ofMinutes(1));
        UUID actionId = UUID.randomUUID();

        ActionOutcome outcome = SystemScope.call(
                business.tenant(),
                () -> budgeted.execute(new ActionRequest(
                        business.tenant(), actionId, "whatsapp.sync", "whatsapp-sync:test", "{}", 1)));

        assertThat(outcome).isInstanceOf(ActionOutcome.Succeeded.class);
        assertThat(((ActionOutcome.Succeeded) outcome).resultJson()).contains("\"templates_complete\": false");
        assertThat(statusOf(business.tenant(), "read_first")).isEqualTo("approved");
        assertThat(idOptional(business.tenant(), "never")).as("not read").isEmpty();
        assertThat(row(missing)).as("a partial listing deletes nothing").startsWith("approved|");
        assertThat(output.getAll())
                .contains("whatsapp.sync.templates_partial action_id=" + actionId + " reason=time_budget pages=1");
    }

    @Test
    void draftsWithoutAMetaIdAreNeverTouched() {
        Business business = business();
        UUID draft = localTemplate(business.tenant(), "draft_only", null, "draft");
        meta.templates(business.waba(), List.of(listed("10001", "something_else", "APPROVED")));

        sync(business);

        assertThat(row(draft)).startsWith("draft|UTILITY||");
    }

    @Test
    void aTemplateWhoseStatusOrNameNorthstarCannotUseIsSkippedAndNeverDeleted(CapturedOutput output) {
        Business business = business();
        UUID unknownStatus = localTemplate(business.tenant(), "odd_status", "11001", "approved");
        meta.templates(
                business.waba(),
                List.of(
                        listed("11001", "odd_status", "SOMETHING_NEW"),
                        new WhatsAppAccountReader.ListedTemplate(
                                "11002", null, "en_US", "APPROVED", "UTILITY", null, BODY)));

        ActionView action = sync(business);

        assertThat(row(unknownStatus))
                .as("kept, and not deleted for being unread")
                .startsWith("approved|");
        assertThat(count(business.tenant())).isOne();
        assertThat(output.getAll())
                .contains("whatsapp.sync.template_skipped action_id=" + action.id()
                        + " code=template_status_unrecognised")
                .contains("whatsapp.sync.template_skipped action_id=" + action.id() + " code=template_unreadable")
                .doesNotContain("SOMETHING_NEW");
    }

    @Test
    void aSyncOlderThanAWebhookAlreadyAppliedChangesNothingForThatTemplate(CapturedOutput output) {
        Business business = business();
        UUID paused = localTemplate(business.tenant(), "newer_webhook", "12001", "paused");
        UUID missingButNewer = localTemplate(business.tenant(), "missing_newer", "12002", "approved");
        admin.sql("update northstar.message_templates set meta_updated_at = now() + interval '1 hour'"
                        + " where tenant_id = :t")
                .param("t", business.tenant())
                .update();
        meta.templates(business.waba(), List.of(listed("12001", "newer_webhook", "APPROVED")));

        ActionView action = sync(business);

        assertThat(row(paused)).startsWith("paused|");
        assertThat(row(missingButNewer)).as("a newer webhook said it exists").startsWith("approved|");
        assertThat(output.getAll())
                .contains("whatsapp.sync.template_stale action_id=" + action.id() + " template_id=" + paused);
    }

    // Failures

    @Test
    void aListingFailureFailsTheSyncAsANumberReadDoes() {
        Business retried = business();
        meta.provider().script(FakeWhatsAppAccountReader.templatesKey(retried.waba(), 0), FakeCall.serverError(503));
        ActionView first = sync(retried);
        assertThat(action(first.id())).isEqualTo("pending|1|retryable|http_503|");

        Business revoked = business();
        meta.provider().script(FakeWhatsAppAccountReader.templatesKey(revoked.waba(), 0), FakeCall.authExpired());
        ActionView second = sync(revoked);
        assertThat(action(second.id())).isEqualTo("paused|0|auth|token_expired|");
        assertThat(admin.sql("select status from northstar.integrations where tenant_id = :t and provider = 'whatsapp'")
                        .param("t", revoked.tenant())
                        .query(String.class)
                        .single())
                .isEqualTo("error");
    }

    // P1, P9, P10

    @Test
    void P1_aSecondSyncWithTheSameListingChangesNothing() {
        Business business = business();
        meta.templates(business.waba(), List.of(listed("13001", "booking_confirmed", "APPROVED")));
        sync(business);
        UUID template = idOf(business.tenant(), "booking_confirmed");
        String stored = row(template);
        String approvedAt = approvedAt(template);
        String metaUpdatedAt = metaUpdatedAt(template);

        ActionView again = sync(business);

        assertThat(result(again.id())).contains("\"templates_changed\": 0", "\"templates_deleted\": 0");
        assertThat(row(template)).isEqualTo(stored);
        assertThat(approvedAt(template)).isEqualTo(approvedAt);
        assertThat(metaUpdatedAt(template)).as("only Meta's time moved on").isGreaterThan(metaUpdatedAt);
        assertThat(count(business.tenant())).isOne();
    }

    @Test
    void P9_eachTenantListsItsOwnAccountWithItsOwnTokenAndNeverTouchesAnotherTenantsTemplates() {
        Business a = business();
        Business b = business();
        UUID ofA = localTemplate(a.tenant(), "booking_confirmed", "14001", "approved");
        UUID ofB = localTemplate(b.tenant(), "booking_confirmed", "14001", "approved");
        meta.templates(a.waba(), List.of(listed("14001", "booking_confirmed", "REJECTED")));

        sync(a);

        assertThat(row(ofA)).startsWith("rejected|");
        assertThat(row(ofB))
                .as("the same Meta ID in another tenant is untouched")
                .startsWith("approved|");
        assertThat(meta.pageReads(a.waba())).singleElement().satisfies(read -> {
            assertThat(read.tenantId()).isEqualTo(a.tenant());
            assertThat(read.token()).isEqualTo(a.token());
        });
        assertThat(meta.pageReads(b.waba())).isEmpty();
    }

    @Test
    void P10_templatesAreWrittenOnlyByTheSystemActorInsideItsTransaction() {
        Business business = business();
        assertThatThrownBy(() -> SystemScope.call(
                        business.tenant(), () -> templates.applyStatus("1", TemplateStatus.APPROVED, Instant.now())))
                .as("joins the caller's transaction, never opens its own")
                .isInstanceOf(IllegalTransactionStateException.class);
        TenantContext member = new TenantContext(business.tenant(), data.user(), Role.ADMIN);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> TenantScope.call(
                        member,
                        () -> transactions.execute(
                                status -> templates.applyStatus("1", TemplateStatus.APPROVED, Instant.now()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Templates are synced from Meta by the system actor");
    }

    @Test
    void logsCarryIdsStatusesAndCodesOnly(CapturedOutput output) {
        Business business = business();
        localTemplate(business.tenant(), "gone_template", "15001", "approved");
        meta.templates(business.waba(), List.of(listed("15002", "secret_campaign_name", "REJECTED")));

        ActionView action = sync(business);

        assertThat(output.getAll())
                .contains("whatsapp.sync.templates action_id=" + action.id()
                        + " pages=1 templates=1 changed=1 deleted=1 skipped=0 complete=true")
                .contains("whatsapp.sync.template_inserted action_id=" + action.id())
                .doesNotContain("secret_campaign_name")
                .doesNotContain("gone_template")
                .doesNotContain("your shoot")
                .doesNotContain("15001")
                .doesNotContain("15002")
                .doesNotContain(business.waba())
                .doesNotContain(business.token());
    }

    // Helpers

    private record Business(UUID tenant, String waba, String token) {}

    private Business business() {
        UUID tenant = data.tenant();
        String waba = "waba-" + UUID.randomUUID();
        data.whatsAppNumber(tenant, waba);
        return new Business(tenant, waba, connect(tenant));
    }

    private String connect(UUID tenant) {
        String token = "EAAG-test-token-" + UUID.randomUUID();
        UUID credential = UUID.randomUUID();
        SystemScope.call(tenant, () -> {
            try (Secret secret = Secret.ofUtf8(token)) {
                return credentials.insertIfAbsent(credential, "whatsapp", secret, null);
            }
        });
        admin.sql("""
                        insert into northstar.integrations (tenant_id, provider, status, credential_id)
                        values (:t, 'whatsapp', 'connected', :c)""").param("t", tenant).param("c", credential).update();
        return token;
    }

    /** A positional UTILITY template in en_US with {@link #BODY}, as Meta lists it. */
    private static WhatsAppAccountReader.ListedTemplate listed(String id, String name, String status) {
        return new WhatsAppAccountReader.ListedTemplate(id, name, "en_US", status, "UTILITY", "POSITIONAL", BODY);
    }

    /** A template the business already has: UTILITY, en_US, with Meta's ID (null: none yet). */
    private UUID localTemplate(UUID tenant, String name, String providerId, String status) {
        return admin.sql("""
                        insert into northstar.message_templates
                          (tenant_id, name, language, category, body, provider_template_id, status)
                        values (:t, :name, 'en_US', 'UTILITY', 'Old body {{1}}', :provider, :status) returning id""")
                .param("t", tenant)
                .param("name", name)
                .param("provider", providerId)
                .param("status", status)
                .query(UUID.class)
                .single();
    }

    /** Runs a new requested sync for the business, in a block of its own, as the worker does. */
    private ActionView sync(Business business) {
        Instant at = Instant.EPOCH.plus(WhatsAppSyncRequests.REQUESTS_SHARE.multipliedBy(BLOCKS.incrementAndGet()));
        ActionView action = SystemScope.call(business.tenant(), () -> syncs.request(at));
        executeAction.run(new ExecuteAction(business.tenant(), action.id()));
        return action;
    }

    /** status|category|provider_template_id|body|variables */
    private String row(UUID template) {
        return admin.sql("""
                        select status || '|' || coalesce(category, '') || '|' || coalesce(provider_template_id, '')
                               || '|' || coalesce(body, '') || '|' || coalesce(variables::text, '')
                        from northstar.message_templates where id = :id""").param("id", template).query(String.class).single();
    }

    private String statusOf(UUID tenant, String name) {
        return admin.sql("select status from northstar.message_templates where tenant_id = :t and name = :n")
                .param("t", tenant)
                .param("n", name)
                .query(String.class)
                .single();
    }

    private UUID idOf(UUID tenant, String name) {
        return idOptional(tenant, name).orElseThrow();
    }

    private java.util.Optional<UUID> idOptional(UUID tenant, String name) {
        return admin.sql("select id from northstar.message_templates where tenant_id = :t and name = :n")
                .param("t", tenant)
                .param("n", name)
                .query(UUID.class)
                .optional();
    }

    private int count(UUID tenant) {
        return admin.sql("select count(*) from northstar.message_templates where tenant_id = :t")
                .param("t", tenant)
                .query(Integer.class)
                .single();
    }

    private String approvedAt(UUID template) {
        return admin.sql("select coalesce(approved_at::text, '') from northstar.message_templates where id = :id")
                .param("id", template)
                .query(String.class)
                .single();
    }

    private String metaUpdatedAt(UUID template) {
        return admin.sql("""
                        select coalesce(to_char(meta_updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US'), '')
                        from northstar.message_templates where id = :id""").param("id", template).query(String.class).single();
    }

    private String result(UUID action) {
        return admin.sql("select result::text from northstar.actions where id = :id")
                .param("id", action)
                .query(String.class)
                .single();
    }

    /** status|attempts|last_error_class|last_error_code|cancel_reason */
    private String action(UUID id) {
        return admin.sql("""
                        select status || '|' || attempts || '|' || coalesce(last_error_class, '') || '|'
                               || coalesce(last_error_code, '') || '|' || coalesce(cancel_reason, '')
                        from northstar.actions where id = :id""").param("id", id).query(String.class).single();
    }
}
