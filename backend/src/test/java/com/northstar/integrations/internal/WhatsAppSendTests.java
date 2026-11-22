package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.NotFoundException;
import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.fakes.FakeCall;
import com.northstar.fakes.FakeClock;
import com.northstar.fakes.Gate;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.integrations.WhatsAppSync;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.ClosedAction;
import com.northstar.workflow.FailureClass;
import com.northstar.workflow.NewAction;
import com.northstar.workflow.OutboundCancellation;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WhatsApp send action (step 2.6a, integration-specs.md section 2) against real Postgres with RLS, the worker's real
 * credential store on its own secrets_user pool, and a fake Cloud API built on the 1.11 kit. Sends run through
 * {@link ExecuteActionHandler}, exactly as the worker runs them, without waiting for JobRunr to poll (the worker is off
 * here, so queued jobs are only stored; the credential store is built for this test as the worker builds it).
 *
 * <p>Covers W33 (the window, read when the send runs), W34 as the send reads it (each status's code when the send runs,
 * and Meta refusing the template; status changes reaching queued sends are TemplateDependentSendsTests), P1 (a
 * send that happened is never repeated once Meta's ID is stored; queueing twice), W32 (a cancelled send fails its
 * message), P9 (another tenant's conversation, template, message, number and token), P10, retries, auth pauses, the
 * accepted duplicate risk, and logs.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, WhatsAppSendTests.WorkerCredentials.class})
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppSendTests {

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

    private final WhatsAppMessages messages;
    private final Actions actions;
    private final OutboundCancellation cancellation;
    private final ExecuteActionHandler executeAction;
    private final WhatsAppSendHandler handler;
    private final FakeWhatsAppSender whatsApp;
    private final FakeClock clock;
    private final CredentialStore credentials;
    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler inboundJob;
    private final OutboundMessages outbound;
    private final WhatsAppConnection connection;
    private final ObjectProvider<CredentialStore> credentialProvider;
    private final ObjectProvider<WhatsAppSync> syncProvider;
    private final JsonMapper json;
    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate transactions;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    WhatsAppSendTests(
            @Autowired WhatsAppMessages messages,
            @Autowired Actions actions,
            @Autowired OutboundCancellation cancellation,
            @Autowired ExecuteActionHandler executeAction,
            @Autowired WhatsAppSendHandler handler,
            @Autowired FakeWhatsAppSender whatsApp,
            @Autowired FakeClock clock,
            @Autowired CredentialStore credentials,
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler inboundJob,
            @Autowired OutboundMessages outbound,
            @Autowired WhatsAppConnection connection,
            @Autowired ObjectProvider<CredentialStore> credentialProvider,
            @Autowired ObjectProvider<WhatsAppSync> syncProvider,
            @Autowired JsonMapper json,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.messages = messages;
        this.actions = actions;
        this.cancellation = cancellation;
        this.executeAction = executeAction;
        this.handler = handler;
        this.whatsApp = whatsApp;
        this.clock = clock;
        this.credentials = credentials;
        this.intake = intake;
        this.inboundJob = inboundJob;
        this.outbound = outbound;
        this.connection = connection;
        this.credentialProvider = credentialProvider;
        this.syncProvider = syncProvider;
        this.json = json;
        this.transactionManager = transactionManager;
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

    // The window (W33)

    @Test
    void W33_textInsideTheWindowIsSentAsText() {
        Business business = business();
        String text = "Your shoot is booked for Saturday " + UUID.randomUUID();

        WhatsAppMessages.Queued queued = queueText(business, text);

        assertThat(queued.created()).isTrue();
        ActionView action = queued.action();
        assertThat(action.type()).isEqualTo("whatsapp.send");
        assertThat(action.status()).isEqualTo("pending");
        assertThat(action.recipientContactId()).isEqualTo(business.contact());
        assertThat(payload(action.id()))
                .as("the payload is the message ID only, never the text")
                .isEqualTo("{\"messageId\": \"" + queued.messageId() + "\"}");
        assertThat(message(queued.messageId())).isEqualTo("queued||");
        assertThat(messageRow(queued.messageId())).isEqualTo("out|system|text|" + text);
        assertThat(whatsApp.sent(queued.messageId()))
                .as("nothing is sent until the worker runs the action")
                .isEmpty();

        run(business, queued);

        List<FakeWhatsAppSender.Sent> sent = whatsApp.sent(queued.messageId());
        assertThat(sent).hasSize(1);
        FakeWhatsAppSender.Sent one = sent.getFirst();
        assertThat(one.body()).isEqualTo(new WhatsAppSender.Text(text));
        assertThat(one.to()).isEqualTo(business.phone());
        assertThat(one.phoneNumberId()).isEqualTo(business.number());
        assertThat(one.token()).isEqualTo(business.token());
        assertThat(one.tenantId()).isEqualTo(business.tenant());
        assertThat(message(queued.messageId())).isEqualTo("sent||" + one.providerMessageId());
        assertThat(sentAtIsSet(queued.messageId())).isTrue();
        assertThat(action(action.id())).isEqualTo("succeeded|1|||");
        assertThat(resultOutcome(action.id())).isEqualTo("sent");
        assertThat(integration(business.tenant())).isEqualTo("connected||true");
    }

    @Test
    void W33_textOutsideTheWindowFailsWindowClosedNoTemplate() {
        Business business = business();
        inboundAt(business, "now() - interval '25 hours'");
        WhatsAppMessages.Queued queued = queueText(business, "Following up on your quote");

        run(business, queued);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isZero();
        assertThat(message(queued.messageId())).isEqualTo("failed|window_closed_no_template|");
        assertThat(action(queued.action().id())).isEqualTo("failed|1|permanent|window_closed_no_template|");
        assertThat(integration(business.tenant()))
                .as("Meta was not called, so the integration's health is unchanged")
                .isEqualTo("connected||false");
    }

    @Test
    void W33_theWindowIsReadWhenTheSendRunsNotWhenItWasQueued() {
        Business closedSince = business();
        WhatsAppMessages.Queued queuedWhileOpen = queueText(closedSince, "Queued inside the window");
        // The send waited (for approval, a retry or a reconnect) and the window closed meanwhile.
        inboundAt(closedSince, "now() - interval '25 hours'");

        Business openedSince = business();
        inboundAt(openedSince, "now() - interval '25 hours'");
        WhatsAppMessages.Queued queuedWhileClosed = queueText(openedSince, "Queued outside the window");
        // The customer wrote again before the send ran.
        inboundAt(openedSince, "now() - interval '1 minute'");

        run(closedSince, queuedWhileOpen);
        run(openedSince, queuedWhileClosed);

        assertThat(message(queuedWhileOpen.messageId())).isEqualTo("failed|window_closed_no_template|");
        assertThat(whatsApp.sent(queuedWhileOpen.messageId())).isEmpty();
        assertThat(message(queuedWhileClosed.messageId())).startsWith("sent||wamid.");
        assertThat(whatsApp.sent(queuedWhileClosed.messageId())).hasSize(1);
    }

    @Test
    void W33_aWindowWithTwoMinutesOrLessLeftCountsAsClosed() {
        Business closing = business();
        inboundAt(closing, "now() - interval '23 hours 58 minutes 30 seconds'"); // 90 seconds left
        Business open = business();
        inboundAt(open, "now() - interval '23 hours 57 minutes'"); // 3 minutes left
        WhatsAppMessages.Queued late = queueText(closing, "Ninety seconds left");
        WhatsAppMessages.Queued inTime = queueText(open, "Three minutes left");

        run(closing, late);
        run(open, inTime);

        assertThat(message(late.messageId())).isEqualTo("failed|window_closed_no_template|");
        assertThat(message(inTime.messageId())).startsWith("sent||wamid.");
    }

    @Test
    void W33_aConversationWithNoInboundMessageHasNoWindow() {
        Business business = business();
        inboundAt(business, "null");
        WhatsAppMessages.Queued queued = queueText(business, "Hello");

        run(business, queued);

        assertThat(message(queued.messageId())).isEqualTo("failed|window_closed_no_template|");
        assertThat(whatsApp.sent(queued.messageId())).isEmpty();
    }

    @Test
    void W33_aTemplateIsSentOutsideTheWindowAndInsideIt() {
        Business business = business();
        inboundAt(business, "now() - interval '25 hours'");
        Template positional = template(business.tenant(), "approved");
        Template named = template(business.tenant(), "approved");

        WhatsAppMessages.Queued byPosition =
                queueTemplate(business, positional, TemplateParameters.positional("Asha", "Saturday 10 am"));
        WhatsAppMessages.Queued byName =
                queueTemplate(business, named, TemplateParameters.named(Map.of("first_name", "Asha")));
        run(business, byPosition);
        run(business, byName);

        assertThat(whatsApp.sent(byPosition.messageId()))
                .singleElement()
                .extracting(FakeWhatsAppSender.Sent::body)
                .isEqualTo(new WhatsAppSender.Template(
                        positional.name(), "en_US", TemplateParameters.positional("Asha", "Saturday 10 am")));
        assertThat(whatsApp.sent(byName.messageId()))
                .singleElement()
                .extracting(FakeWhatsAppSender.Sent::body)
                .isEqualTo(new WhatsAppSender.Template(
                        named.name(), "en_US", TemplateParameters.named(Map.of("first_name", "Asha"))));
        assertThat(message(byPosition.messageId())).startsWith("sent||wamid.");
        assertThat(message(byName.messageId())).startsWith("sent||wamid.");
        assertThat(templateParams(byPosition.messageId())).isEqualTo("[\"Asha\", \"Saturday 10 am\"]");
        assertThat(templateParams(byName.messageId())).isEqualTo("{\"first_name\": \"Asha\"}");
        assertThat(messageRow(byPosition.messageId())).isEqualTo("out|system|template|");

        inboundAt(business, "now() - interval '1 hour'");
        WhatsAppMessages.Queued inside = queueTemplate(business, positional, TemplateParameters.positional("A", "B"));
        run(business, inside);
        assertThat(message(inside.messageId()))
                .as("templates are allowed inside the window too")
                .startsWith("sent||");
    }

    // Template status (W34: the status read when the send runs; what a status change does to queued sends is in
    // TemplateDependentSendsTests, step 2.8c)

    @Test
    void W34_aTemplateThatIsNotApprovedWhenTheSendRunsStopsItWithItsStatusCode() {
        Business business = business();
        Map<String, String> failures = Map.of(
                "draft", "template_not_approved",
                "submitted", "template_not_approved",
                "rejected", "template_rejected",
                "disabled", "template_disabled",
                "deleted", "template_deleted",
                "archived", "template_archived");
        failures.forEach((status, code) -> {
            Template template = template(business.tenant(), "approved");
            WhatsAppMessages.Queued queued = queueTemplate(business, template, TemplateParameters.none());
            // Its status changed after the send was queued, and nothing applied it to the send.
            setStatus(template, status);

            run(business, queued);

            assertThat(whatsApp.sent(queued.messageId())).as(status).isEmpty();
            assertThat(message(queued.messageId())).as(status).isEqualTo("failed|" + code + "|");
            assertThat(action(queued.action().id())).as(status).isEqualTo("failed|1|permanent|" + code + "|");
        });

        Template paused = template(business.tenant(), "approved");
        WhatsAppMessages.Queued queued = queueTemplate(business, paused, TemplateParameters.none());
        setStatus(paused, "paused");

        run(business, queued);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isZero();
        assertThat(action(queued.action().id()))
                .as("a paused template blocks the send without using its attempt, until it is approved again")
                .isEqualTo("paused|0|blocked|template_paused|");
        assertThat(message(queued.messageId())).isEqualTo("queued||");
    }

    @Test
    void W34_aTemplateMetaRefusesFailsTheSendVisiblyAndRequestsASync() {
        Business business = business();
        Template template = template(business.tenant(), "approved");
        WhatsAppMessages.Queued queued = queueTemplate(business, template, TemplateParameters.none());
        whatsApp.provider()
                .script(
                        FakeWhatsAppSender.key(queued.messageId()),
                        new FakeCall.Fail(MetaErrors.classify(400, 132016, null)));

        run(business, queued);

        assertThat(whatsApp.sent(queued.messageId())).isEmpty();
        assertThat(message(queued.messageId())).isEqualTo("failed|template_disabled|");
        assertThat(action(queued.action().id())).isEqualTo("failed|1|permanent|template_disabled|");
        assertThat(SystemScope.call(business.tenant(), actions::failed))
                .as("on the Failed Actions list")
                .extracting(ActionView::id)
                .contains(queued.action().id());
        assertThat(integration(business.tenant()))
                .as("the call's error is recorded on the integration, which stays connected")
                .isEqualTo("connected|template_disabled|false");
        assertThat(count("""
                        select count(*) from northstar.actions
                        where tenant_id = :t and type = 'whatsapp.sync' and idempotency_key like 'whatsapp-sync:requested:%'""", business.tenant()))
                .as("Meta's refusal means the template's status here is out of date: a sync reads it")
                .isOne();
    }

    // A send that happened is never repeated (P1)

    @Test
    void P1_aReRunOnceMetasIdIsStoredNeverSendsAgain() {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Sent once");
        run(business, queued);
        String sentOnce = message(queued.messageId());
        assertThat(whatsApp.sent(queued.messageId())).hasSize(1);

        // The worker died after storing Meta's ID and before recording the attempt: the lease runs out.
        admin.sql("""
                        update northstar.actions set status = 'processing', attempts = 1, result = null,
                               lease_expires_at = now() - interval '1 second' where id = :id""").param("id", queued.action().id()).update();
        run(business, queued);

        assertThat(whatsApp.sent(queued.messageId())).hasSize(1);
        assertThat(action(queued.action().id())).isEqualTo("succeeded|2|||");
        assertThat(resultOutcome(queued.action().id())).isEqualTo("already_sent");
        assertThat(message(queued.messageId())).isEqualTo(sentOnce);

        // A job for the action delivered again after a replay that left it pending.
        admin.sql("""
                        update northstar.actions set status = 'pending', next_attempt_at = now() - interval '1 second'
                        where id = :id""").param("id", queued.action().id()).update();
        run(business, queued);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isOne();
        assertThat(message(queued.messageId())).isEqualTo(sentOnce);
    }

    @Test
    void P1_concurrentRunsOfOneSendSendOnce() throws Exception {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Sent once");
        Gate gate = new Gate();
        whatsApp.provider().script(FakeWhatsAppSender.key(queued.messageId()), FakeCall.gated(gate));

        Future<?> first = threads.submit(() -> run(business, queued));
        assertThat(gate.awaitArrival(WAIT)).isTrue();
        run(business, queued); // the same job again while the first attempt holds its lease
        gate.release();
        first.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(whatsApp.sent(queued.messageId())).hasSize(1);
        assertThat(action(queued.action().id())).isEqualTo("succeeded|1|||");
    }

    @Test
    void P1_queueingTheSameSendTwiceStoresOneMessageAndOneAction() {
        Business business = business();
        String key = key();

        WhatsAppMessages.Queued first = SystemScope.call(
                business.tenant(),
                () -> messages.queueText(business.conversation(), "First", OutboundMessages.Sender.SYSTEM, key));
        WhatsAppMessages.Queued again = SystemScope.call(
                business.tenant(),
                () -> messages.queueText(business.conversation(), "Second", OutboundMessages.Sender.AI, key));

        assertThat(again.created()).isFalse();
        assertThat(again.messageId()).isEqualTo(first.messageId());
        assertThat(again.action().id()).isEqualTo(first.action().id());
        assertThat(admin.sql("""
                        select sender_type || '|' || body from northstar.messages
                        where conversation_id = :c and direction = 'out'""")
                        .param("c", business.conversation())
                        .query(String.class)
                        .list())
                .containsExactly("system|First");
        assertThat(admin.sql("select count(*) from northstar.actions where idempotency_key = :k")
                        .param("k", "whatsapp-send:" + key)
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    // Retries, delays, duplicates and auth

    @Test
    void aSendWhoseAnswerIsLostIsRetriedAndCanReachTheClientTwice() {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "The accepted risk");
        whatsApp.provider().script(FakeWhatsAppSender.key(queued.messageId()), FakeCall.performedButLost());

        run(business, queued);

        assertThat(whatsApp.sent(queued.messageId()))
                .as("Meta sent it, but the answer was lost")
                .hasSize(1);
        assertThat(message(queued.messageId())).isEqualTo("queued||");
        assertThat(action(queued.action().id())).isEqualTo("pending|1|retryable|timeout|");

        makeDue(queued.action().id());
        run(business, queued);

        List<FakeWhatsAppSender.Sent> sent = whatsApp.sent(queued.messageId());
        assertThat(sent)
                .as(
                        "Meta takes no idempotency key: the retry reaches the client again (integration-specs.md section 2)")
                .hasSize(2);
        assertThat(message(queued.messageId())).isEqualTo("sent||" + sent.get(1).providerMessageId());
        assertThat(action(queued.action().id())).isEqualTo("succeeded|2|||");
    }

    @Test
    void retryableFailuresKeepTheMessageQueuedThenFailItWithTheLastCode() {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Retried");
        whatsApp.provider()
                .script(
                        FakeWhatsAppSender.key(queued.messageId()),
                        FakeCall.serverError(503),
                        FakeCall.clientTimeout(),
                        FakeCall.rateLimited(Duration.ofSeconds(1)),
                        FakeCall.serverError(500),
                        FakeCall.serverError(502),
                        FakeCall.rateLimited(Duration.ofSeconds(1)));

        for (int attempt = 1; attempt <= 5; attempt++) {
            run(business, queued);
            assertThat(message(queued.messageId())).as("attempt " + attempt).isEqualTo("queued||");
            assertThat(action(queued.action().id())).as("attempt " + attempt).startsWith("pending|" + attempt + "|");
            makeDue(queued.action().id());
        }
        run(business, queued);

        assertThat(whatsApp.sent(queued.messageId())).isEmpty();
        assertThat(message(queued.messageId())).isEqualTo("failed|http_429|");
        assertThat(action(queued.action().id())).isEqualTo("failed|6|retryable|http_429|");
        assertThat(integration(business.tenant())).isEqualTo("connected|http_429|false");
    }

    @Test
    void aSlowAnswerWithinTheTimeoutIsStored() throws Exception {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Slow");
        whatsApp.provider()
                .script(
                        FakeWhatsAppSender.key(queued.messageId()),
                        FakeCall.delayed(Duration.ofSeconds(20), FakeCall.succeed()));

        Future<?> attempt = threads.submit(() -> run(business, queued));
        assertThat(clock.awaitWaiting(1, WAIT)).isTrue();
        clock.advance(Duration.ofSeconds(20));
        attempt.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(message(queued.messageId())).startsWith("sent||wamid.");
        assertThat(action(queued.action().id())).isEqualTo("succeeded|1|||");
    }

    @Test
    void anAuthFailurePausesTheSendsUntilTheIntegrationReconnects() {
        Business business = business();
        WhatsAppMessages.Queued first = queueText(business, "First");
        whatsApp.provider().script(FakeWhatsAppSender.key(first.messageId()), FakeCall.authExpired());

        run(business, first);

        assertThat(action(first.action().id()))
                .as("the attempt is given back")
                .isEqualTo("paused|0|auth|token_expired|");
        assertThat(message(first.messageId())).isEqualTo("queued||");
        assertThat(integration(business.tenant())).isEqualTo("error|token_expired|false");

        WhatsAppMessages.Queued second = queueText(business, "Second");
        run(business, second);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(second.messageId())))
                .as("the business's other sends pause without calling Meta")
                .isZero();
        assertThat(action(second.action().id())).isEqualTo("paused|0|auth|integration_not_connected|");
        assertThat(message(second.messageId())).isEqualTo("queued||");

        // Reconnecting (a later step) sets the integration back to connected and resumes the paused sends.
        admin.sql("update northstar.integrations set status = 'connected' where tenant_id = :t")
                .param("t", business.tenant())
                .update();
        assertThat(SystemScope.call(business.tenant(), () -> actions.resumePaused("whatsapp.send")))
                .isEqualTo(2);
        run(business, first);
        run(business, second);

        assertThat(whatsApp.sent(first.messageId())).hasSize(1);
        assertThat(whatsApp.sent(second.messageId())).hasSize(1);
        assertThat(message(first.messageId())).startsWith("sent||");
        assertThat(message(second.messageId())).startsWith("sent||");
    }

    @Test
    void aBusinessWhoseWhatsAppIsNotConnectedPausesItsSends() {
        Business neverConnected = business(false);
        Business disconnected = business();
        admin.sql("update northstar.integrations set status = 'disconnected' where tenant_id = :t")
                .param("t", disconnected.tenant())
                .update();
        WhatsAppMessages.Queued none = queueText(neverConnected, "No integration");
        WhatsAppMessages.Queued off = queueText(disconnected, "Disconnected");

        run(neverConnected, none);
        run(disconnected, off);

        for (WhatsAppMessages.Queued queued : List.of(none, off)) {
            assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                    .isZero();
            assertThat(action(queued.action().id())).isEqualTo("paused|0|auth|integration_not_connected|");
            assertThat(message(queued.messageId())).isEqualTo("queued||");
        }
    }

    @Test
    void aCredentialThatCannotBeDecryptedPausesTheSendAndSetsTheIntegrationToError() {
        Business business = business();
        admin.sql("""
                        update secrets.integration_credentials
                        set encrypted_secret = set_byte(encrypted_secret, 20, (get_byte(encrypted_secret, 20) + 1) % 256)
                        where tenant_id = :t""").param("t", business.tenant()).update();
        WhatsAppMessages.Queued queued = queueText(business, "Tampered credential");

        run(business, queued);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isZero();
        assertThat(action(queued.action().id())).isEqualTo("paused|0|auth|credential_unreadable|");
        assertThat(integration(business.tenant())).isEqualTo("error|credential_unreadable|false");
    }

    // Opt-out (W32)

    @Test
    void W32_aSendQueuedForAnOptedOutContactIsCancelledAndItsMessageFailed() {
        Business business = business();
        optOut(business.contact());

        WhatsAppMessages.Queued queued = queueText(business, "To an opted-out contact");

        assertThat(queued.action().status()).isEqualTo("cancelled");
        assertThat(queued.action().cancelReason()).isEqualTo("contact_opted_out");
        assertThat(message(queued.messageId())).isEqualTo("failed|contact_opted_out|");
        run(business, queued);
        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isZero();
    }

    @Test
    void W32_aSendCancelledWhenItIsClaimedFailsItsMessage() {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Queued before the opt-out");
        optOut(business.contact());

        run(business, queued);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isZero();
        assertThat(action(queued.action().id())).isEqualTo("cancelled|0|||contact_opted_out");
        assertThat(message(queued.messageId())).isEqualTo("failed|contact_opted_out|");
    }

    @Test
    void W32_aStopFromTheCustomerFailsTheMessagesOfTheSendsItCancels() {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        data.workflowVersion(tenant);
        connect(tenant);
        String waId = "91" + (9_000_000_000L + ThreadLocalRandom.current().nextLong(999_999_999L));
        runInbound(tenant, store(WhatsAppPayloads.inbound(number).from(waId).text("Hi, we need a shoot")));
        UUID contact = admin.sql("select id from northstar.contacts where tenant_id = :t and normalized_phone = :p")
                .param("t", tenant)
                .param("p", "+" + waId)
                .query(UUID.class)
                .single();
        UUID conversation = admin.sql("select id from northstar.conversations where contact_id = :c")
                .param("c", contact)
                .query(UUID.class)
                .single();
        Business business = new Business(tenant, number, contact, "+" + waId, conversation, null);
        WhatsAppMessages.Queued scheduled = queueText(business, "A reminder for later");
        WhatsAppMessages.Queued paused = queueText(business, "Paused on a token");
        admin.sql("""
                        update northstar.actions set status = 'paused', next_attempt_at = null,
                               last_error_class = 'auth', last_error_code = 'token_expired'
                        where id = :id""").param("id", paused.action().id()).update();

        runInbound(tenant, store(WhatsAppPayloads.inbound(number).from(waId).text("STOP")));

        for (WhatsAppMessages.Queued queued : List.of(scheduled, paused)) {
            assertThat(action(queued.action().id())).startsWith("cancelled|").endsWith("|contact_opted_out");
            assertThat(message(queued.messageId())).isEqualTo("failed|contact_opted_out|");
        }
        run(business, scheduled);
        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(scheduled.messageId())))
                .isZero();
    }

    @Test
    void W32_aSendInFlightWhenTheOptOutLandsKeepsItsSentMessage() throws Exception {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Already on its way");
        Gate gate = new Gate();
        whatsApp.provider().script(FakeWhatsAppSender.key(queued.messageId()), FakeCall.gated(gate));

        Future<?> attempt = threads.submit(() -> run(business, queued));
        assertThat(gate.awaitArrival(WAIT)).isTrue();
        optOut(business.contact());
        int cancelled = SystemScope.call(
                business.tenant(),
                () -> transactions.execute(status -> cancellation.cancelForOptedOutContact(business.contact())));
        gate.release();
        attempt.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(cancelled).as("an attempt in flight cannot be recalled").isZero();
        assertThat(action(queued.action().id())).isEqualTo("succeeded|1|||");
        assertThat(message(queued.messageId())).startsWith("sent||wamid.");
    }

    // Lock order: contact, then conversation, action and message (the inbound job's order, steps 2.4b and 2.5)

    @Test
    void W32_aSendQueuedDuringAnOptOutWaitsForItAndNeverDeadlocks() throws Exception {
        Business business = business();

        Future<WhatsAppMessages.Queued> queueing = optOutInProgress(business.contact(), jdbc -> {
            Future<WhatsAppMessages.Queued> queued =
                    threads.submit(() -> queueText(business, "Queued while the STOP is processed"));
            awaitWaitingForTheContact();
            // The inbound job's next steps: lock the conversation and cancel the contact's actions. The queueing must
            // hold no lock on the conversation while it waits for the contact's, or this would wait for it in turn (a
            // deadlock); lock_timeout makes that a failure here rather than a hang.
            lockConversation(jdbc, business.conversation());
            cancelAsTheInboundJobDoes(jdbc, business.contact());
            return queued;
        });
        WhatsAppMessages.Queued queued = queueing.get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(queued.action().status()).isEqualTo("cancelled");
        assertThat(queued.action().cancelReason()).isEqualTo("contact_opted_out");
        assertThat(message(queued.messageId())).isEqualTo("failed|contact_opted_out|");
    }

    @Test
    void W32_aSendClaimedDuringAnOptOutWaitsForItAndNeverDeadlocks() throws Exception {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Due when the STOP arrives");

        optOutInProgress(business.contact(), jdbc -> {
                    Future<?> claim = threads.submit(() -> run(business, queued));
                    awaitWaitingForTheContact();
                    // The claim must hold neither the action's lock nor the message's while it waits for the contact's:
                    // the inbound job takes the conversation, then the action, then the message (its closing hook).
                    lockConversation(jdbc, business.conversation());
                    assertThat(cancelAsTheInboundJobDoes(jdbc, business.contact()))
                            .isOne();
                    assertThat(jdbc.sql("""
                                            update northstar.messages set status = 'failed', error_code = 'contact_opted_out'
                                            where id = :id and status = 'queued' and provider_message_id is null""").param("id", queued.messageId()).update())
                            .isOne();
                    return claim;
                })
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isZero();
        assertThat(action(queued.action().id())).isEqualTo("cancelled|0|||contact_opted_out");
        assertThat(message(queued.messageId())).isEqualTo("failed|contact_opted_out|");
    }

    // Through the Cloud API adapter (step 2.6b), against a loopback stand-in for Meta

    @Test
    void theCloudApiAdapterSendsWithTheBusinessTokenAndMetasIdIsStored() throws IOException {
        Business business = businessOnAMetaNumber();
        WhatsAppMessages.Queued queued = queueText(business, "Through the Cloud API");
        StandIn meta =
                standIn(200, "{\"messaging_product\": \"whatsapp\", \"messages\": [{\"id\": \"wamid.CLOUD1\"}]}");
        try {
            ActionOutcome outcome = SystemScope.call(
                    business.tenant(), () -> cloudApiHandler(meta).execute(request(business, queued)));

            assertThat(outcome)
                    .isEqualTo(ActionOutcome.succeeded(
                            "{\"messageId\": \"" + queued.messageId() + "\", \"outcome\": \"sent\"}"));
            assertThat(meta.seen().get("path")).isEqualTo("/v26.0/" + business.number() + "/messages");
            assertThat(meta.seen().get("authorization"))
                    .as("the business's own token, decrypted in the worker")
                    .isEqualTo("Bearer " + business.token());
            assertThat(meta.seen().get("body")).contains("\"to\":\"" + business.phone() + "\"");
            assertThat(message(queued.messageId())).isEqualTo("sent||wamid.CLOUD1");
            assertThat(integration(business.tenant())).isEqualTo("connected||true");
        } finally {
            meta.server().stop(0);
        }
    }

    @Test
    void aTokenMetaRejectsSetsTheIntegrationToError() throws IOException {
        Business business = businessOnAMetaNumber();
        WhatsAppMessages.Queued queued = queueText(business, "With an expired token");
        StandIn meta = standIn(
                401,
                "{\"error\": {\"message\": \"Error validating access token\", \"type\": \"OAuthException\","
                        + " \"code\": 190, \"fbtrace_id\": \"AbC123\"}}");
        try {
            ActionOutcome outcome = SystemScope.call(
                    business.tenant(), () -> cloudApiHandler(meta).execute(request(business, queued)));

            assertThat(outcome).isEqualTo(new ActionOutcome.Failed(FailureClass.AUTH, "token_expired", null));
            assertThat(integration(business.tenant())).isEqualTo("error|token_expired|false");
            assertThat(message(queued.messageId())).isEqualTo("queued||");
        } finally {
            meta.server().stop(0);
        }
    }

    // Tenant isolation (P9, P10)

    @Test
    void P9_anotherTenantsConversationOrTemplateCannotBeQueued() {
        Business ours = business();
        Business theirs = business();
        Template theirTemplate = template(theirs.tenant(), "approved");

        assertThatThrownBy(() -> SystemScope.call(
                        ours.tenant(),
                        () -> messages.queueText(
                                theirs.conversation(), "Hello", OutboundMessages.Sender.SYSTEM, key())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> SystemScope.call(
                        ours.tenant(),
                        () -> messages.queueTemplate(
                                ours.conversation(),
                                theirTemplate.id(),
                                TemplateParameters.none(),
                                OutboundMessages.Sender.SYSTEM,
                                key())))
                .isInstanceOf(NotFoundException.class);

        for (UUID tenant : List.of(ours.tenant(), theirs.tenant())) {
            assertThat(count(
                            "select count(*) from northstar.messages where tenant_id = :t and direction = 'out'",
                            tenant))
                    .isZero();
            assertThat(count("select count(*) from northstar.actions where tenant_id = :t", tenant))
                    .isZero();
        }
    }

    @Test
    void P9_eachSendUsesItsOwnBusinessesNumberAndToken() {
        Business a = business();
        Business b = business();
        WhatsAppMessages.Queued fromA = queueText(a, "From A");
        WhatsAppMessages.Queued fromB = queueText(b, "From B");

        run(b, fromB);
        run(a, fromA);

        FakeWhatsAppSender.Sent sentA = whatsApp.sent(fromA.messageId()).getFirst();
        FakeWhatsAppSender.Sent sentB = whatsApp.sent(fromB.messageId()).getFirst();
        assertThat(List.of(sentA.tenantId(), sentA.phoneNumberId(), sentA.token(), sentA.to()))
                .containsExactly(a.tenant(), a.number(), a.token(), a.phone());
        assertThat(List.of(sentB.tenantId(), sentB.phoneNumberId(), sentB.token(), sentB.to()))
                .containsExactly(b.tenant(), b.number(), b.token(), b.phone());
    }

    @Test
    void P9_aSendNamingAnotherTenantsMessageSendsNothing() {
        Business ours = business();
        Business theirs = business();
        WhatsAppMessages.Queued theirMessage = queueText(theirs, "Theirs");
        UUID forged = SystemScope.call(
                        ours.tenant(),
                        () -> actions.create(new NewAction(
                                "whatsapp.send",
                                "forged:" + UUID.randomUUID(),
                                ActionMode.AUTOMATIC,
                                false,
                                WhatsAppSendHandler.payloadFor(theirMessage.messageId()),
                                null,
                                null,
                                ours.contact())))
                .action()
                .id();

        executeAction.run(new ExecuteAction(ours.tenant(), forged));
        executeAction.run(new ExecuteAction(ours.tenant(), theirMessage.action().id())); // their action, our scope

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(theirMessage.messageId())))
                .isZero();
        assertThat(action(forged)).isEqualTo("failed|1|permanent|message_not_found|");
        assertThat(message(theirMessage.messageId()))
                .as("the closing hook, running in our tenant, cannot reach their message")
                .isEqualTo("queued||");
        assertThat(action(theirMessage.action().id())).isEqualTo("pending|0|||");
    }

    @Test
    void P10_queueingWithoutASystemTenantContextFailsLoudly() {
        Business business = business();

        assertThatThrownBy(() -> messages.queueText(
                        business.conversation(), "No context", OutboundMessages.Sender.SYSTEM, key()))
                .isInstanceOf(MissingTenantContextException.class);
        TestData.Member member = data.member("admin");
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                        () -> messages.queueText(
                                business.conversation(), "As an admin", OutboundMessages.Sender.SYSTEM, key())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");

        assertThat(count(
                        "select count(*) from northstar.messages where tenant_id = :t and direction = 'out'",
                        business.tenant()))
                .isZero();
    }

    @Test
    void P10_theSendAndItsClosingRefuseToRunWithoutATenantContext() {
        Business business = business();
        WhatsAppMessages.Queued queued = queueText(business, "Never without a context");
        ActionRequest request = new ActionRequest(
                business.tenant(),
                queued.action().id(),
                "whatsapp.send",
                "whatsapp-send:test",
                WhatsAppSendHandler.payloadFor(queued.messageId()),
                1);
        ClosedAction closing = new ClosedAction(
                queued.action().id(),
                "whatsapp.send",
                WhatsAppSendHandler.payloadFor(queued.messageId()),
                ClosedAction.Closing.FAILED,
                "timeout");

        assertThatThrownBy(() -> handler.execute(request)).isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> handler.closed(closing))
                .as("the closing joins a transaction; it never opens one")
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(whatsApp.provider().calls(FakeWhatsAppSender.key(queued.messageId())))
                .isZero();
        assertThat(message(queued.messageId())).isEqualTo("queued||");
    }

    // Input and logs

    @Test
    void textTemplateValuesAndKeysAreChecked() {
        Business business = business();
        Template template = template(business.tenant(), "approved");

        assertThat(queueText(business, "a".repeat(4096)).created()).isTrue();
        for (String text : List.of(" ", "a".repeat(4097), "nul\u0000inside")) {
            assertThatThrownBy(() -> queueText(business, text)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TemplateParameters.named(Map.of("First-Name", "Asha")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queueTemplate(business, template, TemplateParameters.positional("x".repeat(8200))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 KB");
        assertThatThrownBy(() -> SystemScope.call(
                        business.tenant(),
                        () -> messages.queueText(
                                business.conversation(), "Hi", OutboundMessages.Sender.SYSTEM, "k".repeat(187))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count(
                        "select count(*) from northstar.messages where tenant_id = :t and direction = 'out'",
                        business.tenant()))
                .as("only the 4,096-character message was stored")
                .isOne();
    }

    @Test
    void logsCarryIdsAndCodesOnly(CapturedOutput output) {
        Business business = business();
        String marker = "log-marker-" + UUID.randomUUID();
        WhatsAppMessages.Queued sent = queueText(business, "Sent " + marker);
        WhatsAppMessages.Queued refused = queueText(business, "Refused " + marker);
        WhatsAppMessages.Queued failed = queueText(business, "Failed " + marker);
        whatsApp.provider().script(FakeWhatsAppSender.key(failed.messageId()), FakeCall.rejected("invalid_request"));

        run(business, sent);
        inboundAt(business, "now() - interval '25 hours'");
        run(business, refused);
        inboundAt(business, "now() - interval '1 hour'");
        run(business, failed);

        String wamid = whatsApp.sent(sent.messageId()).getFirst().providerMessageId();
        assertThat(output.getAll())
                .contains("whatsapp.send.queued message_id=" + sent.messageId())
                .contains("whatsapp.send.sent action_id=" + sent.action().id())
                .contains("whatsapp.send.refused action_id=" + refused.action().id())
                .contains("code=window_closed_no_template")
                .contains("whatsapp.send.failed action_id=" + failed.action().id())
                .contains("whatsapp.send.closed action_id=" + failed.action().id())
                .doesNotContain(marker)
                .doesNotContain(business.phone())
                .doesNotContain(business.phone().substring(1))
                .doesNotContain(business.token())
                .doesNotContain(wamid)
                .doesNotContain(business.number());
    }

    // Helpers.

    /** A business with WhatsApp connected and a customer whose window is open (they wrote an hour ago). */
    private record Business(UUID tenant, String number, UUID contact, String phone, UUID conversation, String token) {}

    private Business business() {
        return business(true);
    }

    private Business business(boolean connected) {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        String phone = "+91" + (9_000_000_000L + ThreadLocalRandom.current().nextLong(999_999_999L));
        UUID contact = data.contact(tenant, phone, null);
        UUID conversation = data.conversation(tenant, contact, number, null);
        Business business =
                new Business(tenant, number, contact, phone, conversation, connected ? connect(tenant) : null);
        inboundAt(business, "now() - interval '1 hour'");
        return business;
    }

    /** A business whose number has a Meta-shaped phone number ID (digits), as the Cloud API adapter requires. */
    private Business businessOnAMetaNumber() {
        UUID tenant = data.tenant();
        String number =
                Long.toString(100_000_000_000_000L + ThreadLocalRandom.current().nextLong(899_999_999_999_999L));
        admin.sql("""
                        insert into northstar.whatsapp_accounts (tenant_id, waba_id, phone_number_id)
                        values (:t, :waba, :number)""")
                .param("t", tenant)
                .param("waba", "waba-" + UUID.randomUUID())
                .param("number", number)
                .update();
        String phone = "+91" + (9_000_000_000L + ThreadLocalRandom.current().nextLong(999_999_999L));
        UUID contact = data.contact(tenant, phone, null);
        UUID conversation = data.conversation(tenant, contact, number, null);
        Business business = new Business(tenant, number, contact, phone, conversation, connect(tenant));
        inboundAt(business, "now() - interval '1 hour'");
        return business;
    }

    /** A loopback stand-in for Meta that answers every request the same way, and the last request it saw. */
    private record StandIn(HttpServer server, AtomicReference<Map<String, String>> last) {
        Map<String, String> seen() {
            return last.get();
        }

        URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
    }

    private static StandIn standIn(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<Map<String, String>> last = new AtomicReference<>();
        server.createContext("/", exchange -> {
            last.set(Map.of(
                    "path", exchange.getRequestURI().getRawPath(),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] answer = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return new StandIn(server, last);
    }

    /** The send handler as the app builds it, with the real Cloud API adapter pointed at the stand-in. */
    private WhatsAppSendHandler cloudApiHandler(StandIn meta) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new WhatsAppSendHandler(
                outbound,
                connection,
                credentialProvider,
                syncProvider,
                new CloudApiWhatsAppSender(http, meta.base(), "v26.0", Duration.ofSeconds(5)),
                json,
                transactionManager);
    }

    private static ActionRequest request(Business business, WhatsAppMessages.Queued queued) {
        return new ActionRequest(
                business.tenant(),
                queued.action().id(),
                "whatsapp.send",
                "whatsapp-send:test",
                WhatsAppSendHandler.payloadFor(queued.messageId()),
                1);
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

    private record Template(UUID id, String name) {}

    private Template template(UUID tenant, String status) {
        String name = "shoot_" + UUID.randomUUID().toString().replace("-", "");
        UUID id = admin.sql("""
                        insert into northstar.message_templates (tenant_id, name, language, category, body, status)
                        values (:t, :name, 'en_US', 'UTILITY', 'Hello {{1}}', :status) returning id""")
                .param("t", tenant)
                .param("name", name)
                .param("status", status)
                .query(UUID.class)
                .single();
        return new Template(id, name);
    }

    /** Sets the template's status directly, as if Meta changed it and nothing applied the change to its sends. */
    private void setStatus(Template template, String status) {
        admin.sql("update northstar.message_templates set status = :s where id = :id")
                .param("s", status)
                .param("id", template.id())
                .update();
    }

    private WhatsAppMessages.Queued queueText(Business business, String text) {
        return SystemScope.call(
                business.tenant(),
                () -> messages.queueText(business.conversation(), text, OutboundMessages.Sender.SYSTEM, key()));
    }

    private WhatsAppMessages.Queued queueTemplate(Business business, Template template, TemplateParameters parameters) {
        return SystemScope.call(
                business.tenant(),
                () -> messages.queueTemplate(
                        business.conversation(), template.id(), parameters, OutboundMessages.Sender.SYSTEM, key()));
    }

    private void run(Business business, WhatsAppMessages.Queued queued) {
        executeAction.run(new ExecuteAction(business.tenant(), queued.action().id()));
    }

    /** Sets the conversation's last inbound message to a SQL time expression ("null" for none). */
    private void inboundAt(Business business, String sqlTime) {
        admin.sql("update northstar.conversations set last_inbound_at = " + sqlTime + " where id = :id")
                .param("id", business.conversation())
                .update();
    }

    private void optOut(UUID contact) {
        admin.sql("update northstar.contacts set consent_state = 'opted_out', opt_out_at = now() where id = :id")
                .param("id", contact)
                .update();
    }

    private void makeDue(UUID action) {
        admin.sql("update northstar.actions set next_attempt_at = now() - interval '1 second' where id = :id")
                .param("id", action)
                .update();
    }

    /**
     * Runs the steps in one transaction that first locks the contact for update and opts it out, as the inbound job does
     * (steps 2.4b and 2.5), and commits once they return. Every lock wait in it gives up after 3 seconds.
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

    private static void lockConversation(JdbcClient jdbc, UUID conversation) {
        jdbc.sql("select id from northstar.conversations where id = :id for update")
                .param("id", conversation)
                .query(UUID.class)
                .single();
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

    /** Stores an inbound message through the real intake (step 2.2) and returns its webhook row. */
    private UUID store(WhatsAppPayloads.Inbound inbound) {
        intake.receive(inbound.json().getBytes(StandardCharsets.UTF_8));
        return admin.sql(
                        "select id from northstar.webhook_events where provider = 'whatsapp' and provider_event_id = :e")
                .param("e", "message:" + inbound.wamid())
                .query(UUID.class)
                .single();
    }

    private void runInbound(UUID tenant, UUID webhookEvent) {
        inboundJob.run(new ProcessWhatsAppWebhook(tenant, webhookEvent));
    }

    /** status|error_code|provider_message_id */
    private String message(UUID id) {
        return admin.sql("""
                        select status || '|' || coalesce(error_code, '') || '|' || coalesce(provider_message_id, '')
                        from northstar.messages where id = :id""").param("id", id).query(String.class).single();
    }

    /** direction|sender_type|type|body */
    private String messageRow(UUID id) {
        return admin.sql("""
                        select direction || '|' || sender_type || '|' || type || '|' || coalesce(body, '')
                        from northstar.messages where id = :id""").param("id", id).query(String.class).single();
    }

    private boolean sentAtIsSet(UUID id) {
        return admin.sql("select sent_at is not null from northstar.messages where id = :id")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private String templateParams(UUID id) {
        return admin.sql("select template_params::text from northstar.messages where id = :id")
                .param("id", id)
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

    private String resultOutcome(UUID action) {
        return admin.sql("select result ->> 'outcome' from northstar.actions where id = :id")
                .param("id", action)
                .query(String.class)
                .single();
    }

    /** status|last_error_code|last_success_at set */
    private String integration(UUID tenant) {
        return admin.sql("""
                        select status || '|' || coalesce(last_error_code, '') || '|' || (last_success_at is not null)
                        from northstar.integrations where tenant_id = :t and provider = 'whatsapp'""").param("t", tenant).query(String.class).single();
    }

    private int count(String sql, UUID tenant) {
        return admin.sql(sql).param("t", tenant).query(Integer.class).single();
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }
}
