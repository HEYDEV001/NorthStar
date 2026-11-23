package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.fakes.WhatsAppPayloads;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual lead entry (step 2.10) against the inbound WhatsApp job (steps 2.4b, 2.5) for the same number: both decide a
 * contact's lead under the contact's row lock, so they never open two leads for one contact (the one-open-lead index is
 * the backstop) and never deadlock; a number stored by another writer while an entry runs makes the entry run once
 * more and find it; and a message from a manual lead's contact joins that lead and links its conversation (W1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ManualLeadInboundTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final WhatsAppWebhookIntake intake;
    private final ProcessWhatsAppWebhookHandler inboundJob;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    ManualLeadInboundTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired WhatsAppWebhookIntake intake,
            @Autowired ProcessWhatsAppWebhookHandler inboundJob,
            @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.intake = intake;
        this.inboundJob = inboundJob;
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void W2_anEntryForAReturningCustomerWaitsForTheInboundJobsLockAndThenSeesItsLead() throws Exception {
        for (int round = 0; round < 5; round++) {
            Business business = business();
            String phone = phone();
            UUID contact = data.contact(business.tenant(), phone, null);
            data.closeLead(data.lead(business.tenant(), business.version(), contact, null, null), "lost");
            AtomicReference<Future<MvcTestResult>> entering = new AtomicReference<>();

            asOperator(jdbc -> {
                // The inbound job's order (step 2.4b): the contact locked for update, then its lead decided.
                jdbc.sql("select id from northstar.contacts where id = :id for update")
                        .param("id", contact)
                        .query(UUID.class)
                        .single();
                entering.set(threads.submit(() -> enter(business.staffUser(), phone)));
                awaitWaiting("%contact_for_new_lead%");
                return jdbc.sql("""
                                insert into northstar.leads
                                  (tenant_id, contact_id, workflow_version_id, stage, origin)
                                values (:t, :c, :v, 'new', 'direct')""")
                        .param("t", business.tenant())
                        .param("c", contact)
                        .param("v", business.version())
                        .update();
            });

            MvcTestResult result = entering.get().get(30, TimeUnit.SECONDS);
            assertThat(result).as("round " + round).hasStatus(409);
            assertThat(JSON.readTree(body(result)).get("details").has("leadId"))
                    .as("an unassigned lead the staff member cannot see")
                    .isFalse();
            assertThat(openLeads(contact)).containsExactly("direct");
        }
    }

    @Test
    void anEntryWhoseNumberIsStoredMeanwhileRunsOnceMoreAndFindsTheContact(CapturedOutput output) throws Exception {
        Business business = business();
        String phone = phone();
        AtomicReference<Future<MvcTestResult>> entering = new AtomicReference<>();

        UUID stored = asOperator(jdbc -> {
            // Another writer's new contact for the number, not committed yet: the entry's lookup cannot see it, and its
            // insert waits on the unique index, then fails once this commits.
            UUID contact = jdbc.sql("""
                            insert into northstar.contacts (tenant_id, normalized_phone, name)
                            values (:t, :p, 'Stored Meanwhile') returning id""")
                    .param("t", business.tenant())
                    .param("p", phone)
                    .query(UUID.class)
                    .single();
            entering.set(threads.submit(() -> enter(business.staffUser(), phone)));
            awaitWaiting("%insert into northstar.contacts%");
            return contact;
        });

        MvcTestResult result = entering.get().get(30, TimeUnit.SECONDS);
        assertThat(result).hasStatus(201);
        assertThat(JSON.readTree(body(result)).get("contact").get("id").stringValue())
                .as("the second run found the contact stored meanwhile")
                .isEqualTo(stored.toString());
        assertThat(openLeads(stored)).containsExactly("manual");
        assertThat(count("select count(*) from northstar.request_keys where tenant_id = :id", business.tenant()))
                .as("the lost run's key rolled back with it")
                .isOne();
        assertThat(output)
                .contains("lead.create_retried reason=phone_taken_meanwhile")
                .contains("contact_created=false")
                .contains("retried=true");
    }

    @Test
    void anEntryAndAFirstMessageFromOneNewNumberOpenOneLeadAndNeverDeadlock() throws Exception {
        for (int round = 0; round < 5; round++) {
            Business business = business();
            String phone = phone();

            Race race = race(business, phone);

            assertThat(race.entry().getResponse().getStatus())
                    .as("round " + round)
                    .isIn(201, 409);
            UUID contact = contactOf(business.tenant(), phone);
            assertThat(openLeads(contact)).as("round " + round).hasSize(1);
            assertThat(count(
                            "select count(*) from northstar.messages m join northstar.conversations c"
                                    + " on c.id = m.conversation_id where c.contact_id = :id",
                            contact))
                    .as("the message is stored either way")
                    .isOne();
            if (race.entry().getResponse().getStatus() == 201) {
                assertThat(openLeads(contact)).containsExactly("manual");
                assertThat(linkedConversation(contact))
                        .as("the message joined the manual lead and linked its conversation")
                        .isNotNull();
            } else {
                assertThat(openLeads(contact)).containsExactly("direct");
            }
        }
    }

    @Test
    void W2_anEntryAndAMessageFromAReturningCustomerOpenOneLeadAndNeverDeadlock() throws Exception {
        for (int round = 0; round < 5; round++) {
            Business business = business();
            String phone = phone();
            UUID contact = data.contact(business.tenant(), phone, null);
            data.closeLead(data.lead(business.tenant(), business.version(), contact, null, null), "junk");

            Race race = race(business, phone);

            assertThat(race.entry().getResponse().getStatus())
                    .as("round " + round)
                    .isIn(201, 409);
            assertThat(openLeads(contact)).as("round " + round).hasSize(1);
        }
    }

    @Test
    void W1_aMessageFromAManualLeadsContactJoinsItAndItsNewConversationIsTheMembers() {
        Business business = business();
        String phone = phone();
        MvcTestResult entered = enter(business.staffUser(), phone);
        assertThat(entered).hasStatus(201);
        UUID lead = UUID.fromString(JSON.readTree(body(entered)).get("id").stringValue());

        asWorker(() -> inboundJob.run(new ProcessWhatsAppWebhook(business.tenant(), store(business, phone))));

        UUID contact = contactOf(business.tenant(), phone);
        assertThat(openLeads(contact)).containsExactly("manual");
        UUID conversation = linkedConversation(contact);
        assertThat(conversation).isNotNull();
        assertThat(admin.sql("select assigned_membership_id from northstar.conversations where id = :id")
                        .param("id", conversation)
                        .query(UUID.class)
                        .single())
                .as("a new conversation takes its open lead's assignee (step 2.4b)")
                .isEqualTo(business.staffMembership());
        assertThat(admin.sql("select id from northstar.leads where contact_id = :id")
                        .param("id", contact)
                        .query(UUID.class)
                        .list())
                .containsExactly(lead);
        assertThat(mvc.get()
                        .uri("/api/v1/conversations/" + conversation)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(business.staffUser()))
                        .exchange())
                .as("the member sees the conversation in their inbox")
                .hasStatusOk();
    }

    // Helpers

    private record Business(UUID tenant, String number, UUID version, UUID staffUser, UUID staffMembership) {}

    private record Race(MvcTestResult entry) {}

    private Business business() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        return new Business(
                owner.tenantId(),
                data.whatsAppNumber(owner.tenantId()),
                data.workflowVersion(owner.tenantId()),
                staffUser,
                staffMembership);
    }

    /** The staff member's entry and the number's first message, released together; both must finish. */
    private Race race(Business business, String phone) throws Exception {
        UUID row = store(business, phone);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<MvcTestResult> entry = threads.submit(() -> {
            ready.countDown();
            go.await();
            return enter(business.staffUser(), phone);
        });
        Future<?> inbound = threads.submit(() -> {
            ready.countDown();
            go.await();
            asWorker(() -> inboundJob.run(new ProcessWhatsAppWebhook(business.tenant(), row)));
            return null;
        });
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        inbound.get(60, TimeUnit.SECONDS);
        return new Race(entry.get(60, TimeUnit.SECONDS));
    }

    /** Stores the number's text message through the real intake (step 2.2) and returns its webhook row. */
    private UUID store(Business business, String phone) {
        WhatsAppPayloads.Inbound message = WhatsAppPayloads.inbound(business.number())
                .from(phone.substring(1))
                .sentAt(Instant.now().truncatedTo(ChronoUnit.SECONDS))
                .text("Hello");
        intake.receive(message.json().getBytes(StandardCharsets.UTF_8));
        return admin.sql(
                        "select id from northstar.webhook_events where provider = 'whatsapp' and provider_event_id = :e")
                .param("e", "message:" + message.wamid())
                .query(UUID.class)
                .single();
    }

    private MvcTestResult enter(UUID user, String phone) {
        return mvc.post()
                .uri("/api/v1/leads")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, "manual-lead-inbound-test")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.createObjectNode().put("phone", phone).toString())
                .exchange();
    }

    private static void asWorker(Runnable work) {
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            work.run();
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    /** One transaction as the database's owner, committed once the steps return. Lock waits give up after 3 seconds. */
    private <T> T asOperator(Function<JdbcClient, T> steps) {
        JdbcClient jdbc = JdbcClient.create(postgres.dataSource());
        return new TransactionTemplate(new DataSourceTransactionManager(postgres.dataSource())).execute(status -> {
            jdbc.sql("set local lock_timeout = '3s'").update();
            return steps.apply(jdbc);
        });
    }

    /** Until another session waits for a row lock in a query like this. */
    private void awaitWaiting(String queryLike) {
        await().atMost(WAIT)
                .until(() ->
                        admin.sql("""
                                select count(*) from pg_stat_activity
                                where wait_event_type = 'Lock' and query like :q""").param("q", queryLike).query(Long.class).single() == 1);
    }

    /** The origins of the contact's open leads. */
    private List<String> openLeads(UUID contact) {
        return admin.sql("select origin from northstar.leads where contact_id = :id and closed_at is null")
                .param("id", contact)
                .query(String.class)
                .list();
    }

    private UUID linkedConversation(UUID contact) {
        return admin.sql("select conversation_id from northstar.leads where contact_id = :id and closed_at is null")
                .param("id", contact)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private UUID contactOf(UUID tenant, String phone) {
        return admin.sql("select id from northstar.contacts where tenant_id = :t and normalized_phone = :p")
                .param("t", tenant)
                .param("p", phone)
                .query(UUID.class)
                .single();
    }

    private long count(String sql, UUID id) {
        return admin.sql(sql).param("id", id).query(Long.class).single();
    }

    private static String phone() {
        return "+9195" + (10_000_000 + (long) (Math.random() * 89_999_999L));
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
