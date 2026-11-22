package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.contacts.TemplateStatus;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.ExecuteActionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.node.ObjectNode;

/**
 * A template's sends follow its status (step 2.8c, W34; integration-specs.md section 2, Template status from Meta,
 * Dependent sends), against real Postgres with RLS, Meta's template webhooks through the real intake and job, the sync
 * on the fake account reader, and sends on the fake Cloud API, all run as the worker runs them: a paused template
 * blocks its pending sends and approval resumes them on their schedule; rejected, disabled, deleted and archived fail
 * them visibly; a send waiting for review waits through a pause; a send queued meanwhile is caught when it runs; Meta's
 * 132015 blocks a send and requests a sync, which resumes it; auth and template pauses never resume each other; P1, P9,
 * P10; the lock order against an opt-out of the same contact (repeated five times); and the logs.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TemplateDependentSendsTests.WorkerCredentials.class})
@ExtendWith(OutputCaptureExtension.class)
class TemplateDependentSendsTests {

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

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final long T0 = Instant.parse("2026-11-22T10:00:00Z").getEpochSecond();
    private static final AtomicLong IDS = new AtomicLong(2_487_372_356_000_000L + System.nanoTime() % 1_000_000L);
    private static final AtomicLong BLOCKS =
            new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000));
    private static final String BODY = "Hi {{1}}";

    private final WhatsAppMessages messages;
    private final Actions actions;
    private final ExecuteActionHandler executeAction;
    private final FakeWhatsAppSender whatsApp;
    private final FakeWhatsAppAccountReader meta;
    private final CredentialStore credentials;
    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler job;
    private final WhatsAppSyncRequests syncs;
    private final TemplateDependents dependents;
    private final OutboundMessages outbound;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    TemplateDependentSendsTests(
            @Autowired WhatsAppMessages messages,
            @Autowired Actions actions,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired FakeWhatsAppSender whatsApp,
            @Autowired FakeWhatsAppAccountReader meta,
            @Autowired CredentialStore credentials,
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler job,
            @Autowired WhatsAppSyncRequests syncs,
            @Autowired TemplateDependents dependents,
            @Autowired OutboundMessages outbound,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.messages = messages;
        this.actions = actions;
        this.executeAction = executeAction;
        this.whatsApp = whatsApp;
        this.meta = meta;
        this.credentials = credentials;
        this.intake = intake;
        this.job = job;
        this.syncs = syncs;
        this.dependents = dependents;
        this.outbound = outbound;
        this.transactions = new TransactionTemplate(transactionManager);
        this.postgres = postgres;
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

    // Paused, then approved again (W34)

    @Test
    void W34_aPausedTemplateBlocksItsPendingSendsAndApprovalResumesThemOnTheirSchedule() {
        Business business = business();
        Template template = template(business, "approved");
        WhatsAppMessages.Queued due = queue(business, template);
        WhatsAppMessages.Queued later = queue(business, template);
        admin.sql("update northstar.actions set next_attempt_at = now() + interval '2 hours' where id = :id")
                .param("id", later.action().id())
                .update();
        WhatsAppMessages.Queued retrying = queue(business, template);
        whatsApp.provider().script(FakeWhatsAppSender.key(retrying.messageId()), FakeCall.serverError(503));
        run(business, retrying);
        String laterTime = nextAttemptAt(later.action().id());

        statusWebhook(business, template, "PAUSED", T0);

        assertThat(status(template)).isEqualTo("paused");
        assertThat(action(due)).isEqualTo("paused|0|blocked|template_paused|");
        assertThat(action(later)).isEqualTo("paused|0|blocked|template_paused|");
        assertThat(action(retrying))
                .as("a send waiting for a retry keeps the attempt it used")
                .isEqualTo("paused|1|blocked|template_paused|");
        for (WhatsAppMessages.Queued each : List.of(due, later, retrying)) {
            assertThat(message(each)).as("its message waits, queued").isEqualTo("queued||");
        }
        run(business, due);
        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(due.messageId())))
                .as("a blocked send does not run")
                .isZero();

        statusWebhook(business, template, "APPROVED", T0 + 60);

        assertThat(status(template)).isEqualTo("approved");
        assertThat(action(due)).isEqualTo("pending|0|blocked|template_paused|");
        assertThat(secondsUntilNextAttempt(due.action().id())).isCloseTo(0, within(5.0));
        assertThat(nextAttemptAt(later.action().id()))
                .as("a send scheduled for later keeps its time")
                .isEqualTo(laterTime);
        assertThat(action(retrying)).isEqualTo("pending|1|blocked|template_paused|");
        run(business, due);
        run(business, later);
        assertThat(message(due)).startsWith("sent||wamid.");
        assertThat(action(due)).startsWith("succeeded|1|");
        assertThat(action(later)).as("not due yet").startsWith("pending|0|");
        assertThat(whatsApp.sent(later.messageId())).isEmpty();
    }

    // Rejected, disabled, deleted and archived (W34)

    @Test
    void W34_rejectedDisabledDeletedAndArchivedTemplatesFailTheirSendsVisibly() {
        Map<String, String[]> cases = Map.of(
                "REJECTED", new String[] {"rejected", "template_rejected"},
                "LIMIT_EXCEEDED", new String[] {"rejected", "template_rejected"},
                "DISABLED", new String[] {"disabled", "template_disabled"},
                "PENDING_DELETION", new String[] {"deleted", "template_deleted"},
                "DELETED", new String[] {"deleted", "template_deleted"},
                "ARCHIVED", new String[] {"archived", "template_archived"});
        cases.forEach((event, expected) -> {
            Business business = business();
            Template template = template(business, "approved");
            WhatsAppMessages.Queued pending = queue(business, template);
            WhatsAppMessages.Queued review = queue(business, template);
            needsReview(review);
            WhatsAppMessages.Queued authPaused = queue(business, template);
            pauseForAuth(business, authPaused);
            WhatsAppMessages.Queued blocked = queue(business, template);
            blockAtRun(business, template, blocked);
            WhatsAppMessages.Queued sent = queue(business, template);
            run(business, sent);

            statusWebhook(business, template, event, T0);

            String code = expected[1];
            assertThat(status(template)).as(event).isEqualTo(expected[0]);
            for (WhatsAppMessages.Queued each : List.of(pending, review, authPaused, blocked)) {
                assertThat(action(each)).as(event).isEqualTo("failed|0|permanent|" + code + "|");
                assertThat(message(each)).as(event).isEqualTo("failed|" + code + "|");
            }
            assertThat(SystemScope.call(business.tenant(), actions::failed))
                    .as(event + ": on the Failed Actions list")
                    .extracting(ActionView::id)
                    .contains(
                            pending.action().id(),
                            review.action().id(),
                            authPaused.action().id(),
                            blocked.action().id());
            assertThat(action(sent)).as("a send Meta accepted stays succeeded").startsWith("succeeded|1|");
            assertThat(message(sent)).startsWith("sent||wamid.");
        });
    }

    @Test
    void W34_aSendWaitingForReviewWaitsThroughAPauseAndIsBlockedWhenItRuns() {
        TestData.Member member = data.member("admin");
        Business business = business(member.tenantId());
        Template template = template(business, "approved");
        WhatsAppMessages.Queued review = queue(business, template);
        needsReview(review);

        statusWebhook(business, template, "PAUSED", T0);
        assertThat(action(review)).as("it waits for its approval").isEqualTo("needs_review|0|||");

        TenantScope.call(
                new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                () -> actions.approve(review.action().id()));
        run(business, review);
        assertThat(action(review))
                .as("approved during the pause, it is blocked when it runs")
                .isEqualTo("paused|0|blocked|template_paused|");
        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(review.messageId())))
                .isZero();

        statusWebhook(business, template, "APPROVED", T0 + 60);
        run(business, review);
        assertThat(message(review)).startsWith("sent||wamid.");
    }

    @Test
    void W34_aSendQueuedAfterTheChangeIsCaughtWhenItRuns() {
        Business business = business();
        Template paused = template(business, "approved");
        statusWebhook(business, paused, "PAUSED", T0);
        WhatsAppMessages.Queued queuedWhilePaused = queue(business, paused);
        Template rejected = template(business, "approved");
        statusWebhook(business, rejected, "REJECTED", T0);
        WhatsAppMessages.Queued queuedAfterRejection = queue(business, rejected);

        run(business, queuedWhilePaused);
        run(business, queuedAfterRejection);

        assertThat(action(queuedWhilePaused)).isEqualTo("paused|0|blocked|template_paused|");
        assertThat(action(queuedAfterRejection)).isEqualTo("failed|1|permanent|template_rejected|");
        assertThat(message(queuedAfterRejection)).isEqualTo("failed|template_rejected|");

        statusWebhook(business, paused, "APPROVED", T0 + 60);
        run(business, queuedWhilePaused);
        assertThat(message(queuedWhilePaused)).startsWith("sent||wamid.");
    }

    // Meta refusing the template at send time

    @Test
    void W34_metaRefusingATemplateAsPausedBlocksTheSendAndTheSyncItRequestsResumesIt() {
        Business business = business();
        Template template = template(business, "approved");
        WhatsAppMessages.Queued queued = queue(business, template);
        whatsApp.provider()
                .script(
                        FakeWhatsAppSender.key(queued.messageId()),
                        new FakeCall.Fail(MetaErrors.classify(400, 132015, null)));

        run(business, queued);

        assertThat(action(queued))
                .as("Meta's 132015 blocks the send without using its attempt")
                .isEqualTo("paused|0|blocked|template_paused|");
        assertThat(message(queued)).isEqualTo("queued||");
        assertThat(integration(business.tenant())).isEqualTo("connected|template_paused");
        assertThat(requestedSyncs(business.tenant())).hasSize(1);

        // Meta unpaused it before the sync ran: the listing says approved, as the template here already does.
        meta.templates(business.waba(), List.of(listed(template, "APPROVED")));
        executeAction.run(new ExecuteAction(
                business.tenant(), requestedSyncs(business.tenant()).getFirst()));

        assertThat(status(template)).isEqualTo("approved");
        assertThat(action(queued))
                .as("a write that changes nothing still brings the sends in line with the status")
                .isEqualTo("pending|0|blocked|template_paused|");
        run(business, queued);
        assertThat(message(queued)).startsWith("sent||wamid.");
    }

    @Test
    void W34_metaRefusingATemplateAsNotFoundOrDisabledFailsTheSendAndRequestsASync() {
        for (int[] each : new int[][] {{404, 132001}, {400, 132016}}) {
            Business business = business();
            Template template = template(business, "approved");
            WhatsAppMessages.Queued queued = queue(business, template);
            ActionOutcome.Failed refusal = MetaErrors.classify(each[0], each[1], null);
            whatsApp.provider().script(FakeWhatsAppSender.key(queued.messageId()), new FakeCall.Fail(refusal));

            run(business, queued);

            assertThat(action(queued)).isEqualTo("failed|1|permanent|" + refusal.code() + "|");
            assertThat(message(queued)).isEqualTo("failed|" + refusal.code() + "|");
            assertThat(requestedSyncs(business.tenant())).as("Meta " + each[1]).hasSize(1);
        }
    }

    // Auth pauses and template blocks are separate

    @Test
    void W34_authPausedAndTemplateBlockedSendsNeverResumeEachOther() {
        Business business = business();
        Template template = template(business, "approved");
        WhatsAppMessages.Queued authBefore = queue(business, template);
        pauseForAuth(business, authBefore);
        WhatsAppMessages.Queued blocked = queue(business, template);

        statusWebhook(business, template, "PAUSED", T0);
        assertThat(action(authBefore))
                .as("a pause blocks pending sends only")
                .isEqualTo("paused|0|auth|integration_not_connected|");
        assertThat(action(blocked)).isEqualTo("paused|0|blocked|template_paused|");

        assertThat(SystemScope.call(business.tenant(), () -> actions.resumePaused("whatsapp.send")))
                .as("a reconnect resumes the auth pause only")
                .isOne();
        assertThat(action(blocked)).isEqualTo("paused|0|blocked|template_paused|");

        WhatsAppMessages.Queued authAfter = queue(business, template);
        // Paused for auth as the executor pauses it (the integration is connected again by now).
        admin.sql("""
                        update northstar.actions set status = 'paused', next_attempt_at = null, last_error_class = 'auth',
                          last_error_code = 'integration_not_connected'
                        where id = :id""").param("id", authAfter.action().id()).update();

        statusWebhook(business, template, "APPROVED", T0 + 60);
        assertThat(action(blocked)).isEqualTo("pending|0|blocked|template_paused|");
        assertThat(action(authAfter))
                .as("approval resumes the template's blocks only")
                .isEqualTo("paused|0|auth|integration_not_connected|");
    }

    // The sync (W34)

    @Test
    void W34_theSyncBringsSendsInLineWithEachTemplatesStatus() {
        Business business = business();
        Template template = template(business, "approved");
        WhatsAppMessages.Queued first = queue(business, template);

        meta.templates(business.waba(), List.of(listed(template, "PAUSED")));
        sync(business);
        assertThat(action(first)).isEqualTo("paused|0|blocked|template_paused|");

        meta.templates(business.waba(), List.of(listed(template, "APPROVED")));
        sync(business);
        assertThat(action(first)).isEqualTo("pending|0|blocked|template_paused|");

        WhatsAppMessages.Queued second = queue(business, template);
        meta.templates(business.waba(), List.of());
        sync(business);
        assertThat(status(template)).as("missing from a complete listing").isEqualTo("deleted");
        for (WhatsAppMessages.Queued each : List.of(first, second)) {
            assertThat(action(each)).isEqualTo("failed|0|permanent|template_deleted|");
            assertThat(message(each)).isEqualTo("failed|template_deleted|");
        }
    }

    // P1, P9, P10

    @Test
    void P1_theSameStatusAgainChangesNothingMore(CapturedOutput output) {
        Business business = business();
        Template template = template(business, "approved");
        WhatsAppMessages.Queued queued = queue(business, template);

        statusWebhook(business, template, "PAUSED", T0);
        UUID replay = statusWebhook(business, template, "PAUSED", T0 + 1);
        assertThat(action(queued)).isEqualTo("paused|0|blocked|template_paused|");

        statusWebhook(business, template, "APPROVED", T0 + 2);
        int jobs = jobsFor(queued.action().id());
        UUID approvedAgain = statusWebhook(business, template, "APPROVED", T0 + 3);
        assertThat(jobsFor(queued.action().id()))
                .as("resumed once, queued once")
                .isEqualTo(jobs);

        statusWebhook(business, template, "REJECTED", T0 + 4);
        UUID rejectedAgain = statusWebhook(business, template, "REJECTED", T0 + 5);
        assertThat(action(queued)).isEqualTo("failed|0|permanent|template_rejected|");
        assertThat(message(queued)).isEqualTo("failed|template_rejected|");

        String sends = "whatsapp.template.sends template_id=" + template.id();
        assertThat(output.getAll().lines().filter(line -> line.contains(sends)))
                .as("one line for each change that did something, none for its repeats")
                .hasSize(3);
        for (UUID row : List.of(replay, approvedAgain, rejectedAgain)) {
            assertThat(processing(row)).isEqualTo("true|");
        }
        assertThat(output.getAll()
                        .lines()
                        .filter(line -> line.contains("whatsapp.send.closed action_id="
                                + queued.action().id())))
                .as("its closing hook ran once")
                .hasSize(1);
    }

    @Test
    void P9_aTemplateChangeTouchesOnlyItsOwnTenantsSends() {
        Business mine = business();
        Business theirs = business();
        Template myTemplate = template(mine, "approved");
        // Meta's template IDs are unique per tenant only (step 2.8b): the other tenant's has the same one.
        Template theirTemplate = template(theirs, "approved", myTemplate.providerId());
        WhatsAppMessages.Queued mySend = queue(mine, myTemplate);
        WhatsAppMessages.Queued theirSend = queue(theirs, theirTemplate);

        statusWebhook(mine, myTemplate, "PAUSED", T0);
        assertThat(action(mySend)).isEqualTo("paused|0|blocked|template_paused|");
        assertThat(action(theirSend)).isEqualTo("pending|0|||");

        statusWebhook(mine, myTemplate, "REJECTED", T0 + 60);
        assertThat(action(mySend)).isEqualTo("failed|0|permanent|template_rejected|");
        assertThat(action(theirSend)).isEqualTo("pending|0|||");
        assertThat(message(theirSend)).isEqualTo("queued||");
        assertThat(status(theirTemplate)).isEqualTo("approved");

        List<UUID> seen = SystemScope.call(
                mine.tenant(),
                () -> transactions.execute(status -> outbound.lockQueuedForTemplate(theirTemplate.id())));
        assertThat(seen)
                .as("another tenant's template has no queued messages here")
                .isEmpty();
    }

    @Test
    void P10_theDependentsRunOnlyInTheTemplatesTransactionAsTheSystemActor() {
        Business business = business();
        Template template = template(business, "approved");
        WhatsAppMessages.Queued queued = queue(business, template);

        assertThatThrownBy(() -> SystemScope.call(
                        business.tenant(), () -> dependents.apply(template.id(), TemplateStatus.PAUSED)))
                .as("no transaction to join")
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.execute(status -> outbound.lockQueuedForTemplate(template.id())))
                .as("no tenant context")
                .isInstanceOf(MissingTenantContextException.class);
        TestData.Member member = data.member("admin");
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                        () -> transactions.execute(status -> outbound.lockQueuedForTemplate(template.id()))))
                .as("a user, even an admin")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");

        assertThat(action(queued)).isEqualTo("pending|0|||");
    }

    // Lock order: the template, its queued messages' contacts (shared, by ID), their actions, their messages

    @Test
    void W34_aRejectionDuringAnOptOutOfTheSameContactNeverDeadlocks() throws Exception {
        for (int round = 1; round <= 5; round++) {
            Business business = business();
            Template template = template(business, "approved");
            WhatsAppMessages.Queued first = queue(business, template);
            WhatsAppMessages.Queued second = queue(business, template);
            UUID row = deliver(business, statusChange(template, "REJECTED"), T0);
            // Actions are locked in ID order: a job that went for them without the contact's lock would take the lower
            // one and wait for the higher, which the opt-out holds while it waits for the lower (a deadlock).
            UUID higher = first.action().id().compareTo(second.action().id()) > 0
                    ? first.action().id()
                    : second.action().id();

            Future<?> rejection = optOutInProgress(business.contact(), jdbc -> {
                jdbc.sql("select id from northstar.actions where id = :id for update")
                        .param("id", higher)
                        .query(UUID.class)
                        .single();
                Future<?> running = threads.submit(() -> runJob(business, row));
                // The job holds the template and waits for the contact, holding no action.
                awaitWaitingForTheContact();
                assertThat(cancelAsTheInboundJobDoes(jdbc, business.contact())).isEqualTo(2);
                assertThat(jdbc.sql("""
                                        update northstar.messages set status = 'failed', error_code = 'contact_opted_out'
                                        where conversation_id = :c and status = 'queued' and provider_message_id is null""").param("c", business.conversation()).update())
                        .isEqualTo(2);
                return running;
            });
            rejection.get(WAIT.toSeconds(), TimeUnit.SECONDS);

            String as = "round " + round;
            assertThat(status(template)).as(as).isEqualTo("rejected");
            assertThat(processing(row)).as(as).isEqualTo("true|");
            for (WhatsAppMessages.Queued each : List.of(first, second)) {
                assertThat(action(each)).as(as).isEqualTo("cancelled|0|||contact_opted_out");
                assertThat(message(each)).as(as).isEqualTo("failed|contact_opted_out|");
            }
        }
    }

    // Logs

    @Test
    void logsCarryIdsAndCodesOnly(CapturedOutput output) {
        Business business = business();
        Template template = template(business, "approved");
        WhatsAppMessages.Queued queued = queue(business, template);

        statusWebhook(business, template, "PAUSED", T0);
        statusWebhook(business, template, "REJECTED", T0 + 60);

        assertThat(output.getAll())
                .contains("whatsapp.template.sends template_id=" + template.id()
                        + " status=paused blocked=1 resumed=0 failed=0 source=webhook")
                .contains("action.blocked action_id=" + queued.action().id()
                        + " type=whatsapp.send code=template_paused at=dependency")
                .contains("whatsapp.template.sends template_id=" + template.id()
                        + " status=rejected blocked=0 resumed=0 failed=1 source=webhook")
                .contains("whatsapp.send.closed action_id=" + queued.action().id() + " message_id=" + queued.messageId()
                        + " closing=failed code=template_rejected message_failed=true")
                .doesNotContain(template.providerId())
                .doesNotContain(template.name())
                .doesNotContain(business.waba())
                .doesNotContain(business.phone());
    }

    // Helpers

    /** A business with WhatsApp connected on one WABA, and a customer whose window is open. */
    private record Business(UUID tenant, String waba, String number, UUID contact, String phone, UUID conversation) {}

    /** A template of the business: Northstar's ID, Meta's, and its name. */
    private record Template(UUID id, String providerId, String name) {}

    private Business business() {
        return business(data.tenant());
    }

    private Business business(UUID tenant) {
        String waba = WhatsAppPayloads.wabaId();
        String number = data.whatsAppNumber(tenant, waba);
        String phone = "+91" + (9_000_000_000L + ThreadLocalRandom.current().nextLong(999_999_999L));
        UUID contact = data.contact(tenant, phone, null);
        UUID conversation = data.conversation(tenant, contact, number, null);
        connect(tenant);
        admin.sql("update northstar.conversations set last_inbound_at = now() - interval '1 hour' where id = :id")
                .param("id", conversation)
                .update();
        return new Business(tenant, waba, number, contact, phone, conversation);
    }

    private void connect(UUID tenant) {
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
    }

    private Template template(Business business, String status) {
        return template(business, status, Long.toString(IDS.incrementAndGet()));
    }

    /** UTILITY, en_US, with the body and variables Meta would list for it, so a sync listing it changes nothing else. */
    private Template template(Business business, String status, String providerId) {
        String name = "booking_secret_name_" + IDS.incrementAndGet();
        UUID id = admin.sql("""
                        insert into northstar.message_templates
                          (tenant_id, name, language, category, body, variables, provider_template_id, status)
                        values (:t, :name, 'en_US', 'UTILITY', :body,
                                '{"format": "positional", "names": ["1"]}', :provider, :status)
                        returning id""")
                .param("t", business.tenant())
                .param("name", name)
                .param("body", BODY)
                .param("provider", providerId)
                .param("status", status)
                .query(UUID.class)
                .single();
        return new Template(id, providerId, name);
    }

    private static WhatsAppAccountReader.ListedTemplate listed(Template template, String status) {
        return new WhatsAppAccountReader.ListedTemplate(
                template.providerId(), template.name(), "en_US", status, "UTILITY", "POSITIONAL", BODY);
    }

    private WhatsAppMessages.Queued queue(Business business, Template template) {
        return SystemScope.call(
                business.tenant(),
                () -> messages.queueTemplate(
                        business.conversation(),
                        template.id(),
                        TemplateParameters.none(),
                        OutboundMessages.Sender.SYSTEM,
                        "test:" + UUID.randomUUID()));
    }

    private void run(Business business, WhatsAppMessages.Queued queued) {
        executeAction.run(new ExecuteAction(business.tenant(), queued.action().id()));
    }

    /** As an approval-mode send the Phase 3 engine creates: waiting for review. */
    private void needsReview(WhatsAppMessages.Queued queued) {
        admin.sql("update northstar.actions set status = 'needs_review', next_attempt_at = null where id = :id")
                .param("id", queued.action().id())
                .update();
    }

    /** Pauses the send as auth by running it while WhatsApp is disconnected, then reconnects. */
    private void pauseForAuth(Business business, WhatsAppMessages.Queued queued) {
        setIntegration(business, "disconnected");
        run(business, queued);
        setIntegration(business, "connected");
        assertThat(action(queued)).isEqualTo("paused|0|auth|integration_not_connected|");
    }

    /** Blocks the send by running it while the template reads paused here, then sets the template back. */
    private void blockAtRun(Business business, Template template, WhatsAppMessages.Queued queued) {
        setStatus(template, "paused");
        run(business, queued);
        setStatus(template, "approved");
        assertThat(action(queued)).isEqualTo("paused|0|blocked|template_paused|");
    }

    private void setIntegration(Business business, String status) {
        admin.sql("update northstar.integrations set status = :s where tenant_id = :t and provider = 'whatsapp'")
                .param("s", status)
                .param("t", business.tenant())
                .update();
    }

    private void setStatus(Template template, String status) {
        admin.sql("update northstar.message_templates set status = :s where id = :id")
                .param("s", status)
                .param("id", template.id())
                .update();
    }

    /** A status webhook's value, shaped as Meta's reference shows it. */
    private static ObjectNode statusChange(Template template, String event) {
        return WhatsAppPayloads.object()
                .put("event", event)
                .put("message_template_id", Long.parseLong(template.providerId()))
                .put("message_template_name", template.name())
                .put("message_template_language", "en_US")
                .put("message_template_category", "UTILITY")
                .put("reason", "NONE");
    }

    /** Delivers the status change through the real intake and runs its job, as the worker does; returns its row. */
    private UUID statusWebhook(Business business, Template template, String event, long time) {
        UUID row = deliver(business, statusChange(template, event), time);
        runJob(business, row);
        return row;
    }

    /** Stores the change through the real intake (routed by its WABA); returns its new row. */
    private UUID deliver(Business business, ObjectNode value, long time) {
        String field = WhatsAppTemplateUpdate.STATUS_FIELD;
        List<UUID> before = rows(business.waba(), field);
        intake.receive(WhatsAppPayloads.delivery()
                .accountChange(business.waba(), time, field, value)
                .json()
                .getBytes(StandardCharsets.UTF_8));
        List<UUID> after = rows(business.waba(), field);
        after.removeAll(before);
        assertThat(after).hasSize(1);
        return after.getFirst();
    }

    private List<UUID> rows(String waba, String field) {
        return new ArrayList<>(admin.sql("""
                        select id from northstar.webhook_events
                        where provider = 'whatsapp' and provider_event_id like :prefix order by received_at, id""")
                .param("prefix", field + ":" + waba + ":%")
                .query(UUID.class)
                .list());
    }

    private void runJob(Business business, UUID row) {
        job.run(new ProcessWhatsAppWebhook(business.tenant(), row));
    }

    /** Runs a new requested sync for the business, in a block of its own, as the worker does. */
    private void sync(Business business) {
        Instant at = Instant.EPOCH.plus(WhatsAppSyncRequests.REQUESTS_SHARE.multipliedBy(BLOCKS.incrementAndGet()));
        ActionView action = SystemScope.call(business.tenant(), () -> syncs.request(at));
        executeAction.run(new ExecuteAction(business.tenant(), action.id()));
    }

    private List<UUID> requestedSyncs(UUID tenant) {
        return admin.sql("""
                        select id from northstar.actions
                        where tenant_id = :t and type = 'whatsapp.sync' and idempotency_key like 'whatsapp-sync:requested:%'""").param("t", tenant).query(UUID.class).list();
    }

    /**
     * Runs the steps in one transaction that first locks the contact for update and opts it out, as the inbound job
     * does (steps 2.4b and 2.5), and commits once they return. Every lock wait in it gives up after 3 seconds.
     */
    private <T> T optOutInProgress(UUID contact, Function<JdbcClient, T> steps) {
        JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
        return new TransactionTemplate(new DataSourceTransactionManager(postgres.dataSource())).execute(status -> {
            jdbc.sql("set local lock_timeout = '3s'").update();
            jdbc.sql("select id from northstar.contacts where id = :id for update")
                    .param("id", contact)
                    .query(UUID.class)
                    .single();
            jdbc.sql("update northstar.contacts set consent_state = 'opted_out', opt_out_at = now() where id = :id")
                    .param("id", contact)
                    .update();
            return steps.apply(jdbc);
        });
    }

    /** The inbound job's cancellation of the contact's actions (step 2.5), in the opt-out's transaction. */
    private static int cancelAsTheInboundJobDoes(JdbcClient jdbc, UUID contact) {
        return jdbc.sql("""
                        update northstar.actions
                        set status = 'cancelled', cancel_reason = 'contact_opted_out', next_attempt_at = null
                        where recipient_contact_id = :c and status in ('pending', 'needs_review', 'paused')""").param("c", contact).update();
    }

    /** Until another session waits for a lock on a shared read of a contact. */
    private void awaitWaitingForTheContact() {
        await().atMost(WAIT).until(() -> admin.sql("""
                                select count(*) from pg_stat_activity
                                where wait_event_type = 'Lock' and query like '%northstar.contacts%for share%'""").query(Long.class).single() == 1);
    }

    private String status(Template template) {
        return admin.sql("select status from northstar.message_templates where id = :id")
                .param("id", template.id())
                .query(String.class)
                .single();
    }

    /** status|attempts|last_error_class|last_error_code|cancel_reason */
    private String action(WhatsAppMessages.Queued queued) {
        return admin.sql("""
                        select status || '|' || attempts || '|' || coalesce(last_error_class, '') || '|'
                               || coalesce(last_error_code, '') || '|' || coalesce(cancel_reason, '')
                        from northstar.actions where id = :id""")
                .param("id", queued.action().id())
                .query(String.class)
                .single();
    }

    /** status|error_code|provider_message_id */
    private String message(WhatsAppMessages.Queued queued) {
        return admin.sql("""
                        select status || '|' || coalesce(error_code, '') || '|' || coalesce(provider_message_id, '')
                        from northstar.messages where id = :id""")
                .param("id", queued.messageId())
                .query(String.class)
                .single();
    }

    /** status|last_error_code */
    private String integration(UUID tenant) {
        return admin.sql("""
                        select status || '|' || coalesce(last_error_code, '')
                        from northstar.integrations where tenant_id = :t and provider = 'whatsapp'""").param("t", tenant).query(String.class).single();
    }

    /** processed|error */
    private String processing(UUID row) {
        return admin.sql("""
                        select (processed_at is not null) || '|' || coalesce(error, '')
                        from northstar.webhook_events where id = :id""").param("id", row).query(String.class).single();
    }

    private String nextAttemptAt(UUID action) {
        return admin.sql("select coalesce(next_attempt_at::text, '') from northstar.actions where id = :id")
                .param("id", action)
                .query(String.class)
                .single();
    }

    private double secondsUntilNextAttempt(UUID action) {
        return admin.sql(
                        "select extract(epoch from next_attempt_at - now())::float8 from northstar.actions where id = :id")
                .param("id", action)
                .query(Double.class)
                .single();
    }

    private int jobsFor(UUID actionId) {
        return admin.sql("select count(*) from jobrunr.jobrunr_jobs where jobasjson like :id")
                .param("id", "%" + actionId + "%")
                .query(Integer.class)
                .single();
    }
}
