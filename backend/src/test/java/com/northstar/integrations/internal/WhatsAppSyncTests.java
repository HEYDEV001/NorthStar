package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.Gate;
import com.northstar.integrations.WhatsAppSync;
import com.northstar.jobs.Jobs;
import com.northstar.jobs.RecurringPlatformJob;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The WhatsApp sync (step 2.8a, integration-specs.md section 2, Quality and limits) against real Postgres with RLS, the
 * worker's real credential store, and a fake number reader built on the 1.11 kit (and, at the end, the real Cloud API
 * reader against a loopback stand-in). Syncs run through {@link ExecuteActionHandler}, as the worker runs them.
 *
 * <p>Covers what a sync records, Meta's values it keeps or refuses, a limit missing from Meta's answer or refused by the
 * pinned Graph API version (the sync never fails for it), the quality-drop warning, retries, auth pauses, P1 (a re-run,
 * requests in one block, two syncs at once), P9 (each tenant's numbers and token), P10, the schedule, and logs.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, WhatsAppSyncTests.WorkerCredentials.class})
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppSyncTests {

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

    /** Each test's syncs get their own request blocks, so every sync it asks for is a new action. */
    private static final AtomicLong BLOCKS =
            new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000));

    private final WhatsAppSyncRequests syncs;
    private final WhatsAppSync sync;
    private final Actions actions;
    private final ExecuteActionHandler executeAction;
    private final FakeWhatsAppAccountReader meta;
    private final CredentialStore credentials;
    private final WhatsAppConnection connection;
    private final WhatsAppAccountHealth accounts;
    private final com.northstar.contacts.MessageTemplates templates;
    private final TemplateDependents dependents;
    private final ObjectProvider<CredentialStore> credentialProvider;
    private final Jobs jobs;
    private final RequestWhatsAppSyncHandler scheduledSync;
    private final List<RecurringPlatformJob> recurringJobs;
    private final PlatformTransactionManager transactionManager;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    WhatsAppSyncTests(
            @Autowired WhatsAppSyncRequests syncs,
            @Autowired WhatsAppSync sync,
            @Autowired Actions actions,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeWhatsAppAccountReader meta,
            @Autowired CredentialStore credentials,
            @Autowired WhatsAppConnection connection,
            @Autowired WhatsAppAccountHealth accounts,
            @Autowired com.northstar.contacts.MessageTemplates templates,
            @Autowired TemplateDependents dependents,
            @Autowired ObjectProvider<CredentialStore> credentialProvider,
            @Autowired Jobs jobs,
            @Autowired RequestWhatsAppSyncHandler scheduledSync,
            @Autowired List<RecurringPlatformJob> recurringJobs,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.syncs = syncs;
        this.sync = sync;
        this.actions = actions;
        this.executeAction = executeAction;
        this.meta = meta;
        this.credentials = credentials;
        this.connection = connection;
        this.accounts = accounts;
        this.templates = templates;
        this.dependents = dependents;
        this.credentialProvider = credentialProvider;
        this.jobs = jobs;
        this.scheduledSync = scheduledSync;
        this.recurringJobs = recurringJobs;
        this.transactionManager = transactionManager;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** Jobs run on worker threads with no web request; Spring's test support binds one to the test thread. */
    @BeforeEach
    void runLikeAWorkerThread() {
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    // What a sync records.

    @Test
    void aSyncRecordsEachNumbersQualityAndLimitReadWithTheBusinessToken() {
        Business business = business(2);
        meta.answer(business.number(0), "GREEN", "TIER_2K");
        meta.answer(business.number(1), "YELLOW", "TIER_2K");

        ActionView action = newSync(business);
        assertThat(action.type()).isEqualTo("whatsapp.sync");
        assertThat(action.status()).isEqualTo("pending");
        assertThat(action.recipientContactId()).as("a sync messages nobody").isNull();
        assertThat(payload(action.id())).isEqualTo("{\"trigger\": \"requested\"}");

        run(business, action);

        assertThat(account(business.account(0))).isEqualTo("green|tier_2k");
        assertThat(account(business.account(1))).isEqualTo("yellow|tier_2k");
        assertThat(meta.reads())
                .filteredOn(read -> read.tenantId().equals(business.tenant()))
                .hasSize(2)
                .allSatisfy(read -> assertThat(read.token()).isEqualTo(business.token()))
                .extracting(FakeWhatsAppAccountReader.Read::phoneNumberId)
                .containsExactly(business.number(0), business.number(1));
        assertThat(action(action.id())).isEqualTo("succeeded|1|||");
        assertThat(result(action.id())).startsWith("{\"changed\": 2, \"numbers\": 2, ");
        assertThat(integration(business.tenant())).isEqualTo("connected||true");
    }

    @Test
    void naAndUnknownBothBecomeUnknownAndMetasCaseDoesNotMatter() {
        Business business = business(3);
        meta.answer(business.number(0), "NA", "TIER_UNLIMITED");
        meta.answer(business.number(1), "UNKNOWN", "TIER_NOT_SET");
        meta.answer(business.number(2), "Red", "TIER_50");

        run(business, newSync(business));

        assertThat(account(business.account(0))).isEqualTo("unknown|tier_unlimited");
        assertThat(account(business.account(1))).isEqualTo("unknown|tier_not_set");
        assertThat(account(business.account(2))).isEqualTo("red|tier_50");
    }

    @Test
    void aLimitMissingFromMetasAnswerKeepsTheStoredOneAndTheSyncSucceeds(CapturedOutput output) {
        Business business = business(2);
        setAccount(business.account(0), "green", "tier_10k");
        setAccount(business.account(1), "green", "tier_10k");
        meta.answer(business.number(0), "YELLOW", null);
        // As the pinned v26.0 may: the Graph API refuses the field, and the number is read without it.
        meta.answerWithoutTheLimitField(business.number(1), "RED");

        ActionView action = newSync(business);
        run(business, action);

        assertThat(action(action.id())).isEqualTo("succeeded|1|||");
        assertThat(account(business.account(0))).isEqualTo("yellow|tier_10k");
        assertThat(account(business.account(1))).isEqualTo("red|tier_10k");
        assertThat(output.getAll())
                .contains("whatsapp.sync.value_kept action_id=" + action.id() + " account_id=" + business.account(0)
                        + " code=messaging_limit_absent reason=not_in_answer")
                .contains("whatsapp.sync.value_kept action_id=" + action.id() + " account_id=" + business.account(1)
                        + " code=messaging_limit_absent reason=field_refused");
        assertThat(integration(business.tenant())).isEqualTo("connected||true");
    }

    @Test
    void aNumberNeverReadKeepsNoLimitWhenMetaSendsNone() {
        Business business = business(1);
        meta.answer(business.number(0), null, null);

        ActionView action = newSync(business);
        run(business, action);

        assertThat(action(action.id())).isEqualTo("succeeded|1|||");
        assertThat(account(business.account(0))).isEqualTo("|");
    }

    @Test
    void aValueNorthstarDoesNotRecogniseKeepsWhatIsStoredWithAWarning(CapturedOutput output) {
        Business business = business(1);
        setAccount(business.account(0), "yellow", "tier_2k");
        meta.answer(business.number(0), "PURPLE", "TIER_1K");

        ActionView action = newSync(business);
        run(business, action);

        assertThat(action(action.id())).isEqualTo("succeeded|1|||");
        assertThat(account(business.account(0))).isEqualTo("yellow|tier_2k");
        assertThat(output.getAll())
                .contains("WARN")
                .contains("whatsapp.sync.value_kept action_id=" + action.id() + " account_id=" + business.account(0)
                        + " code=quality_rating_unrecognised")
                .contains("code=messaging_limit_unrecognised")
                .doesNotContain("PURPLE")
                .doesNotContain("TIER_1K");
    }

    @Test
    void aDropInQualityIsAWarningAndARiseIsNot(CapturedOutput output) {
        Business business = business(1);
        UUID account = business.account(0);
        String dropped = "whatsapp.account.quality_dropped account_id=" + account;

        meta.answer(business.number(0), "GREEN", "TIER_250");
        run(business, newSync(business));
        assertThat(output.getAll()).as("the first rating is no drop").doesNotContain(dropped);

        meta.answer(business.number(0), "YELLOW", "TIER_250");
        run(business, newSync(business));
        assertThat(output.getAll()).contains(dropped + " from=green to=yellow");

        meta.answer(business.number(0), "RED", "TIER_250");
        run(business, newSync(business));
        assertThat(output.getAll()).contains(dropped + " from=yellow to=red");

        meta.answer(business.number(0), "NA", "TIER_250");
        run(business, newSync(business));
        meta.answer(business.number(0), "GREEN", "TIER_250");
        run(business, newSync(business));
        assertThat(output.getAll())
                .as("a number without a rating, and a rise, are no drop")
                .doesNotContain(dropped + " from=red")
                .doesNotContain(dropped + " from=unknown");
        assertThat(account(account)).isEqualTo("green|tier_250");
    }

    // P1

    @Test
    void P1_aSecondSyncWithTheSameAnswersChangesNothing() {
        Business business = business(2);
        meta.answer(business.number(0), "GREEN", "TIER_2K");
        meta.answer(business.number(1), "GREEN", "TIER_2K");
        run(business, newSync(business));
        String updatedAt = updatedAt(business.account(0));

        ActionView again = newSync(business);
        run(business, again);

        assertThat(action(again.id())).isEqualTo("succeeded|1|||");
        assertThat(result(again.id())).startsWith("{\"changed\": 0, \"numbers\": 2, ");
        assertThat(updatedAt(business.account(0))).as("not even rewritten").isEqualTo(updatedAt);
        assertThat(account(business.account(0))).isEqualTo("green|tier_2k");
    }

    @Test
    void P1_requestsInTheSameTenMinutesShareOneActionAndScheduledOnesOneASixHourBlock() {
        Business business = business(1);
        Instant start = Instant.parse("2026-11-22T10:00:00Z");

        ActionView first = SystemScope.call(business.tenant(), () -> syncs.request(start));
        ActionView sameBlock = SystemScope.call(business.tenant(), () -> syncs.request(start.plusSeconds(599)));
        ActionView nextBlock = SystemScope.call(business.tenant(), () -> syncs.request(start.plusSeconds(600)));
        assertThat(sameBlock.id()).isEqualTo(first.id());
        assertThat(nextBlock.id()).isNotEqualTo(first.id());
        assertThat(idempotencyKey(first.id())).isEqualTo("whatsapp-sync:requested:" + start.getEpochSecond() / 600);

        long block = WhatsAppSyncRequests.block(start, Duration.ofHours(6));
        scheduledSync.run(new RequestWhatsAppSync(business.tenant(), block));
        scheduledSync.run(new RequestWhatsAppSync(business.tenant(), block));
        assertThat(count(
                        "select count(*) from northstar.actions where tenant_id = :t and idempotency_key = :k",
                        business.tenant(),
                        "whatsapp-sync:scheduled:" + block))
                .as("the scheduled job run twice creates one sync")
                .isOne();
        assertThat(block).as("2026-11-22 06:00 to 12:00 UTC").isEqualTo(start.getEpochSecond() / 21_600);
    }

    @Test
    void P1_twoSyncsAtOnceBothSucceedAndRecordTheSameValues() throws Exception {
        Business business = business(1);
        meta.answer(business.number(0), "YELLOW", "TIER_10K");
        Gate gate = new Gate();
        meta.provider().script(FakeWhatsAppAccountReader.key(business.number(0)), FakeCall.gated(gate));
        ActionView first = newSync(business);
        ActionView second = newSync(business);

        Future<?> held = threads.submit(() -> run(business, first));
        assertThat(gate.awaitArrival(Duration.ofSeconds(10))).isTrue();
        run(business, second); // reads and records while the first read is still open
        assertThat(account(business.account(0))).isEqualTo("yellow|tier_10k");
        gate.release();
        held.get(10, TimeUnit.SECONDS);

        assertThat(action(first.id())).isEqualTo("succeeded|1|||");
        assertThat(action(second.id())).isEqualTo("succeeded|1|||");
        assertThat(result(first.id())).as("the second sync already recorded it").contains("\"changed\": 0");
        assertThat(account(business.account(0))).isEqualTo("yellow|tier_10k");
    }

    // Failures.

    @Test
    void retryableFailuresAreRetriedTwiceAndThenFailVisibly() {
        Business business = business(1);
        meta.provider()
                .script(
                        FakeWhatsAppAccountReader.key(business.number(0)),
                        FakeCall.serverError(503),
                        FakeCall.rateLimited(Duration.ofSeconds(90)),
                        FakeCall.clientTimeout());
        ActionView action = newSync(business);

        run(business, action);
        assertThat(action(action.id())).isEqualTo("pending|1|retryable|http_503|");
        assertThat(secondsUntilNextAttempt(action.id())).isBetween(20L, 31L);

        makeDue(action.id());
        run(business, action);
        assertThat(action(action.id())).isEqualTo("pending|2|retryable|http_429|");
        assertThat(secondsUntilNextAttempt(action.id()))
                .as("Meta's Retry-After is shorter than the 5-minute wait")
                .isBetween(290L, 301L);

        makeDue(action.id());
        run(business, action);
        assertThat(action(action.id())).isEqualTo("failed|3|retryable|timeout|");
        assertThat(SystemScope.call(business.tenant(), actions::failed))
                .as("on the Failed Actions list")
                .extracting(ActionView::id)
                .contains(action.id());
        assertThat(integration(business.tenant()))
                .as("a failed read is the integration's last error, not a disconnection")
                .isEqualTo("connected|timeout|false");
        assertThat(account(business.account(0))).isEqualTo("|");
    }

    @Test
    void aPermanentFailureFailsAtOnce() {
        Business business = business(1);
        meta.provider()
                .script(FakeWhatsAppAccountReader.key(business.number(0)), FakeCall.rejected("invalid_parameter"));
        ActionView action = newSync(business);

        run(business, action);

        assertThat(action(action.id())).isEqualTo("failed|1|permanent|invalid_parameter|");
    }

    @Test
    void anAuthFailurePausesTheSyncAndSetsTheIntegrationToError() {
        Business business = business(2);
        meta.provider().script(FakeWhatsAppAccountReader.key(business.number(0)), FakeCall.authExpired());
        ActionView action = newSync(business);

        run(business, action);

        assertThat(action(action.id())).isEqualTo("paused|0|auth|token_expired|");
        assertThat(integration(business.tenant())).isEqualTo("error|token_expired|false");
        assertThat(meta.reads(business.number(1)))
                .as("it stops at the first failed read")
                .isEmpty();

        ActionView next = newSync(business);
        run(business, next);
        assertThat(action(next.id()))
                .as("the next sync pauses before calling Meta")
                .isEqualTo("paused|0|auth|integration_not_connected|");
        assertThat(meta.reads(business.number(0))).hasSize(1);
    }

    @Test
    void aBusinessWithoutWhatsAppConnectedPausesWithoutCallingMeta() {
        Business business = business(1, false);
        ActionView action = newSync(business);

        run(business, action);

        assertThat(action(action.id())).isEqualTo("paused|0|auth|integration_not_connected|");
        assertThat(meta.reads(business.number(0))).isEmpty();
    }

    // P9 and P10

    @Test
    void P9_eachTenantsSyncReadsOnlyItsOwnNumbersWithItsOwnToken() {
        Business a = business(1);
        Business b = business(1);
        meta.answer(a.number(0), "RED", "TIER_250");
        meta.answer(b.number(0), "GREEN", "TIER_100K");
        setAccount(b.account(0), "yellow", "tier_10k");

        ActionView syncA = newSync(a);
        // A job naming A's sync in B's tenant finds nothing there and reads nothing.
        executeAction.run(new ExecuteAction(b.tenant(), syncA.id()));
        assertThat(meta.reads(a.number(0))).isEmpty();
        assertThat(action(syncA.id())).isEqualTo("pending|0|||");

        run(a, syncA);

        assertThat(meta.reads(a.number(0))).singleElement().satisfies(read -> {
            assertThat(read.token()).isEqualTo(a.token());
            assertThat(read.tenantId()).isEqualTo(a.tenant());
        });
        assertThat(meta.reads(b.number(0))).isEmpty();
        assertThat(account(a.account(0))).isEqualTo("red|tier_250");
        assertThat(account(b.account(0)))
                .as("another tenant's number is untouched")
                .isEqualTo("yellow|tier_10k");
    }

    @Test
    void P10_aSyncIsRequestedAndRecordedOnlyByTheSystemActorInsideItsScope() {
        Business business = business(1);
        assertThatThrownBy(sync::request).isInstanceOf(MissingTenantContextException.class);
        TenantContext admin = new TenantContext(business.tenant(), data.user(), Role.ADMIN);
        assertThatThrownBy(() -> TenantScope.call(admin, sync::request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WhatsApp syncs are requested by the system actor");
        assertThatThrownBy(() -> SystemScope.call(business.tenant(), accounts::numbers))
                .as("joins the caller's transaction, never opens its own")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // The schedule.

    @Test
    void theScheduleRunsHourlyAndQueuesOneSyncPerConnectedTenantPerBlock() {
        assertThat(recurringJobs)
                .filteredOn(job -> job.id().equals("schedule-whatsapp-syncs"))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.interval()).isEqualTo(Duration.ofHours(1));
                    assertThat(job.request()).isInstanceOf(ScheduleWhatsAppSyncs.class);
                });

        // Only this test's tenant, so no other test's tenant gets a sync queued; which tenants the lookup lists is
        // WhatsAppTenantScanTests'.
        Business connected = business(1);
        WhatsAppSyncScheduler oneTenant = new WhatsAppSyncScheduler(listing(connected.tenant()), jobs);
        Instant now = Instant.now();
        long block = WhatsAppSyncRequests.block(now, Duration.ofHours(6));

        oneTenant.schedule(now);
        oneTenant.schedule(now); // the next hourly run in the same block

        UUID job = WhatsAppSyncScheduler.jobIdFor(connected.tenant(), block);
        assertThat(jobExists(job)).isTrue();
        assertThat(jobJson(job))
                .as("the job carries IDs only: its tenant and its block")
                .contains(connected.tenant().toString(), Long.toString(block), RequestWhatsAppSync.class.getName());
        assertThat(WhatsAppSyncScheduler.jobIdFor(connected.tenant(), block + 1))
                .as("the next block is another job")
                .isNotEqualTo(job);
    }

    /** A tenant directory whose WhatsApp scan lists exactly these tenants. */
    private static TenantDirectory listing(UUID... tenants) {
        return new TenantDirectory() {
            @Override
            public boolean currentTenantExists() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<UUID> currentMembershipId() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<UUID> tenantsWithDueActions() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<UUID> tenantsWithUnprocessedWebhooks(Duration olderThan) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<UUID> tenantsWithWhatsApp() {
                return List.of(tenants);
            }
        };
    }

    // Through the Cloud API reader.

    @Test
    void throughTheCloudApiReaderTheDecryptedTokenReachesMetaAndARefusedLimitIsKept(CapturedOutput output)
            throws IOException {
        Business business = businessOnMetaNumbers();
        setAccount(business.account(0), "green", "tier_2k");
        StandIn graph = standIn();
        try {
            graph.answers().add(new String[] {
                "400",
                "{\"error\": {\"message\": \"(#100) Tried accessing nonexisting"
                        + " field (whatsapp_business_manager_messaging_limit)\", \"type\": \"OAuthException\","
                        + " \"code\": 100, \"fbtrace_id\": \"AbC-123_xyz\"}}"
            });
            graph.answers().add(new String[] {"200", "{\"quality_rating\": \"YELLOW\", \"id\": \"1\"}"});
            // Then the account's templates (step 2.8b): none.
            graph.answers().add(new String[] {"200", "{\"data\": [], \"paging\": {\"cursors\": {}}}"});
            CloudApiWhatsAppAccountReader cloudApi =
                    new CloudApiWhatsAppAccountReader(http(), graph.base(), "v26.0", Duration.ofSeconds(5));
            WhatsAppSyncHandler handler = new WhatsAppSyncHandler(
                    connection,
                    accounts,
                    credentialProvider,
                    cloudApi,
                    new WhatsAppTemplateSync(cloudApi, templates, dependents, transactionManager),
                    transactionManager);
            UUID actionId = UUID.randomUUID();

            var outcome = SystemScope.call(
                    business.tenant(),
                    () -> handler.execute(new ActionRequest(
                            business.tenant(), actionId, "whatsapp.sync", "whatsapp-sync:test", "{}", 1)));

            assertThat(outcome).isInstanceOf(com.northstar.workflow.ActionOutcome.Succeeded.class);
            assertThat(graph.seen())
                    .hasSize(3)
                    .allSatisfy(request -> assertThat(request).endsWith("|Bearer " + business.token()));
            assertThat(graph.seen().subList(0, 2))
                    .allSatisfy(request -> assertThat(request).startsWith("/v26.0/" + business.number(0) + "?"));
            assertThat(graph.seen().get(2)).startsWith("/v26.0/" + business.waba() + "/message_templates?");
            assertThat(account(business.account(0))).isEqualTo("yellow|tier_2k");
            assertThat(output.getAll())
                    .contains("code=messaging_limit_absent reason=field_refused")
                    .contains("whatsapp.account.quality_dropped account_id=" + business.account(0)
                            + " from=green to=yellow")
                    .doesNotContain(business.token())
                    .doesNotContain(business.number(0));
        } finally {
            graph.server().stop(0);
        }
    }

    @Test
    void logsCarryIdsValuesAndCodesOnly(CapturedOutput output) {
        Business business = business(1);
        meta.answer(business.number(0), "GREEN", "TIER_2K");
        ActionView action = newSync(business);

        run(business, action);

        assertThat(output.getAll())
                .contains("whatsapp.sync.requested action_id=" + action.id() + " trigger=requested created=true")
                .contains("whatsapp.sync.number_recorded action_id=" + action.id() + " account_id="
                        + business.account(0) + " quality=green limit=tier_2k changed=true")
                .contains("whatsapp.sync.done action_id=" + action.id() + " attempt=1 numbers=1 changed=1")
                .doesNotContain(business.token())
                .doesNotContain(business.number(0))
                .doesNotContain(business.waba());
    }

    // Helpers.

    /** A business with its numbers under one WABA, and WhatsApp connected with a stored token (or not). */
    private record Business(UUID tenant, String waba, List<String> numbers, List<UUID> accounts, String token) {
        String number(int i) {
            return numbers.get(i);
        }

        UUID account(int i) {
            return accounts.get(i);
        }
    }

    private Business business(int numbers) {
        return business(numbers, true);
    }

    private Business business(int numbers, boolean connected) {
        UUID tenant = data.tenant();
        String waba = "waba-" + UUID.randomUUID();
        List<String> ids = new CopyOnWriteArrayList<>();
        for (int i = 0; i < numbers; i++) ids.add(data.whatsAppNumber(tenant, waba));
        return new Business(tenant, waba, List.copyOf(ids), accountIds(ids), connected ? connect(tenant) : null);
    }

    /** A business whose number has a Meta-shaped phone number ID (digits), as the Cloud API reader requires. */
    private Business businessOnMetaNumbers() {
        UUID tenant = data.tenant();
        String waba =
                Long.toString(100_000_000_000_000L + ThreadLocalRandom.current().nextLong(899_999_999_999_999L));
        String number =
                Long.toString(100_000_000_000_000L + ThreadLocalRandom.current().nextLong(899_999_999_999_999L));
        admin.sql("""
                        insert into northstar.whatsapp_accounts (tenant_id, waba_id, phone_number_id)
                        values (:t, :waba, :number)""")
                .param("t", tenant)
                .param("waba", waba)
                .param("number", number)
                .update();
        return new Business(tenant, waba, List.of(number), accountIds(List.of(number)), connect(tenant));
    }

    private List<UUID> accountIds(List<String> numbers) {
        return numbers.stream()
                .map(number -> admin.sql("select id from northstar.whatsapp_accounts where phone_number_id = :n")
                        .param("n", number)
                        .query(UUID.class)
                        .single())
                .toList();
    }

    /** Stores a business token through the worker's store and connects WhatsApp with it; returns the token. */
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

    /** A new requested sync for the business, in a block of its own. */
    private ActionView newSync(Business business) {
        Instant at = Instant.EPOCH.plus(WhatsAppSyncRequests.REQUESTS_SHARE.multipliedBy(BLOCKS.incrementAndGet()));
        return SystemScope.call(business.tenant(), () -> syncs.request(at));
    }

    private void run(Business business, ActionView action) {
        executeAction.run(new ExecuteAction(business.tenant(), action.id()));
    }

    private void makeDue(UUID action) {
        admin.sql("update northstar.actions set next_attempt_at = now() - interval '1 second' where id = :id")
                .param("id", action)
                .update();
    }

    private void setAccount(UUID account, String quality, String limit) {
        admin.sql("update northstar.whatsapp_accounts set quality_rating = :q, messaging_limit = :l where id = :id")
                .param("q", quality)
                .param("l", limit)
                .param("id", account)
                .update();
    }

    /** quality_rating|messaging_limit */
    private String account(UUID id) {
        return admin.sql("""
                        select coalesce(quality_rating, '') || '|' || coalesce(messaging_limit, '')
                        from northstar.whatsapp_accounts where id = :id""").param("id", id).query(String.class).single();
    }

    private String updatedAt(UUID account) {
        return admin.sql("select coalesce(updated_at::text, '') from northstar.whatsapp_accounts where id = :id")
                .param("id", account)
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

    private String payload(UUID action) {
        return admin.sql("select payload::text from northstar.actions where id = :id")
                .param("id", action)
                .query(String.class)
                .single();
    }

    private String result(UUID action) {
        return admin.sql("select result::text from northstar.actions where id = :id")
                .param("id", action)
                .query(String.class)
                .single();
    }

    private String idempotencyKey(UUID action) {
        return admin.sql("select idempotency_key from northstar.actions where id = :id")
                .param("id", action)
                .query(String.class)
                .single();
    }

    private long secondsUntilNextAttempt(UUID action) {
        return admin.sql("""
                        select floor(extract(epoch from (next_attempt_at - now())))::bigint
                        from northstar.actions where id = :id""").param("id", action).query(Long.class).single();
    }

    /** status|last_error_code|last_success_at set */
    private String integration(UUID tenant) {
        return admin.sql("""
                        select status || '|' || coalesce(last_error_code, '') || '|' || (last_success_at is not null)
                        from northstar.integrations where tenant_id = :t and provider = 'whatsapp'""").param("t", tenant).query(String.class).single();
    }

    private int count(String sql, UUID tenant, String key) {
        return admin.sql(sql)
                .param("t", tenant)
                .param("k", key)
                .query(Integer.class)
                .single();
    }

    private boolean jobExists(UUID jobId) {
        return admin.sql("select count(*) from jobrunr.jobrunr_jobs where id = :id")
                        .param("id", jobId.toString())
                        .query(Integer.class)
                        .single()
                == 1;
    }

    private String jobJson(UUID jobId) {
        return admin.sql("select jobasjson from jobrunr.jobrunr_jobs where id = :id")
                .param("id", jobId.toString())
                .query(String.class)
                .single();
    }

    /** A loopback stand-in for the Graph API: answers {status, body} in order (the last repeats), and what it saw. */
    private record StandIn(HttpServer server, Deque<String[]> answers, List<String> seen) {
        URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
    }

    private static StandIn standIn() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        Deque<String[]> answers = new ArrayDeque<>();
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestURI().getRawPath() + "?"
                    + exchange.getRequestURI().getRawQuery() + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            String[] answer;
            synchronized (answers) {
                answer = answers.size() > 1 ? answers.poll() : answers.peek();
            }
            byte[] body = answer[1].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(answer[0]), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new StandIn(server, answers, seen);
    }

    private static HttpClient http() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
