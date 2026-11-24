package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.TestcontainersConfiguration.WhatsAppTestSecrets;
import com.northstar.devtools.PostSignedWebhookTwice.Answers;
import com.northstar.devtools.PostSignedWebhookTwice.Message;
import com.northstar.integrations.internal.ProcessWhatsAppWebhook;
import com.northstar.integrations.internal.ProcessWhatsAppWebhookHandler;
import com.northstar.tenancy.TenantDirectory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The Phase 2 gate's signed webhook (step 2.11) against the real endpoint on its own port: the app accepts its
 * signature, the second post of the same bytes changes nothing (P1), a message can be dated past the window (W33) or be
 * an opt-out (W32), and it only ever acts as a synthetic sender and only ever posts to the webhook.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PostSignedWebhookTwiceTests {

    private final PostSignedWebhookTwice tool = new PostSignedWebhookTwice();
    private final WhatsAppTestSecrets secrets;
    private final ProcessWhatsAppWebhookHandler inboundJob;
    private final SeedDevWorkflow seed;
    private final TestData data;
    private final JdbcClient admin;

    @LocalServerPort
    private int port;

    PostSignedWebhookTwiceTests(
            @Autowired WhatsAppTestSecrets secrets,
            @Autowired ProcessWhatsAppWebhookHandler inboundJob,
            @Autowired JdbcClient jdbc,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired TenantDirectory tenants,
            @Autowired PostgresAdmin postgres) {
        this.secrets = secrets;
        this.inboundJob = inboundJob;
        this.seed = new SeedDevWorkflow(jdbc, transactionManager, tenants);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void P1_theSecondPostOfTheSameSignedWebhookCreatesNothing(CapturedOutput output) {
        Business business = business();
        Message message = message(business, "Hello from the gate", 0);

        Answers answers = tool.postTwice(webhook(), message, secret());

        assertThat(answers.first()).isEqualTo(200);
        assertThat(answers.second()).isEqualTo(200);
        assertThat(events(answers)).isOne();
        process(business, answers);
        String contact = "+" + message.from();
        assertThat(rows(business, contact))
                .as("contacts, open leads in new, conversations, messages")
                .isEqualTo("1|1|1|1");
        assertThat(admin.sql("select body from northstar.messages where provider_message_id = :w")
                        .param("w", answers.wamid())
                        .query(String.class)
                        .single())
                .isEqualTo("Hello from the gate");

        Answers again = tool.postTwice(webhook(), message(business, "Another message", 0, message.from()), secret());
        process(business, again);
        assertThat(rows(business, contact))
                .as("a new message is stored, and joins the same contact, lead and conversation")
                .isEqualTo("1|1|1|2");
        assertThat(output).doesNotContain(secrets.signer().secret(), message.from());
    }

    @Test
    void W33_aMessageDatedPastTheWindowLeavesItClosed() {
        Business business = business();
        Message message = message(business, "Yesterday", 25);

        Answers answers = tool.postTwice(webhook(), message, secret());
        process(business, answers);

        assertThat(admin.sql("""
                        select (c.window_expires_at < now()) || '|' || (c.last_inbound_at < now() - interval '24 hours')
                        from northstar.conversations c join northstar.contacts ct on ct.id = c.contact_id
                        where ct.tenant_id = :t and ct.normalized_phone = :p""")
                        .param("t", business.tenant())
                        .param("p", "+" + message.from())
                        .query(String.class)
                        .single())
                .isEqualTo("true|true");
    }

    @Test
    void W32_aStopFromASyntheticSenderOptsThatContactOut() {
        Business business = business();
        Message hello = message(business, "Hello", 0);
        process(business, tool.postTwice(webhook(), hello, secret()));

        process(business, tool.postTwice(webhook(), message(business, "STOP", 0, hello.from()), secret()));

        assertThat(admin.sql("""
                        select consent_state || '|' || (opt_out_at is not null) from northstar.contacts
                        where tenant_id = :t and normalized_phone = :p""")
                        .param("t", business.tenant())
                        .param("p", "+" + hello.from())
                        .query(String.class)
                        .single())
                .isEqualTo("opted_out|true");
    }

    @Test
    void P15_aWrongSecretIsRefusedBothTimesAndStoresNothing() {
        Business business = business();

        Answers answers = tool.postTwice(
                webhook(), message(business, "Forged", 0), "0".repeat(64).getBytes(StandardCharsets.UTF_8));

        assertThat(answers.first()).isEqualTo(401);
        assertThat(answers.second()).isEqualTo(401);
        assertThat(events(answers)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://gate.trycloudflare.com/webhooks/whatsapp",
                "http://127.0.0.1:8088/webhooks/whatsapp",
                "http://localhost:8080/webhooks/whatsapp",
                "http://[::1]:8080/webhooks/whatsapp"
            })
    void itPostsToTheWebhookOverHttpsOrOnThisMachine(String url) {
        assertThatCode(() -> PostSignedWebhookTwice.checked(URI.create(url))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://gate.trycloudflare.com/webhooks/whatsapp", // plain HTTP to another machine
                "https://gate.trycloudflare.com/api/v1/me",
                "https://gate.trycloudflare.com/webhooks/whatsapp/",
                "https://gate.trycloudflare.com/webhooks/whatsapp?x=1",
                "https://gate.trycloudflare.com/webhooks/whatsapp#x",
                "https://user@gate.trycloudflare.com/webhooks/whatsapp",
                "https:///webhooks/whatsapp",
                "ftp://127.0.0.1/webhooks/whatsapp",
                "/webhooks/whatsapp"
            })
    void itPostsNowhereElse(String url) {
        assertThatThrownBy(() -> PostSignedWebhookTwice.checked(URI.create(url)))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("url_invalid"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"910000000001", "910000999999"})
    void theSenderIsSynthetic(String from) {
        assertThat(new Message("pn", "waba", from, "Hi", 0).from()).isEqualTo(from);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "919876543210",
                "+910000000001",
                "9100000000011",
                "91000000000",
                "15550001234",
                "",
                "910000abcdef"
            })
    void aRealNumberIsRefusedAsASender(String from) {
        assertThatThrownBy(() -> new Message("pn", "waba", from, "Hi", 0))
                .isInstanceOfSatisfying(LocalToolException.class, e -> {
                    assertThat(e.code()).isEqualTo("argument_invalid");
                    assertThat(e.getMessage()).contains("synthetic");
                });
    }

    @Test
    void otherValuesAreChecked() {
        for (Runnable bad : new Runnable[] {
            () -> new Message("has space", "waba", "910000000001", "Hi", 0),
            () -> new Message("pn", "", "910000000001", "Hi", 0),
            () -> new Message("pn", "waba", "910000000001", " ", 0),
            () -> new Message("pn", "waba", "910000000001", "x".repeat(1001), 0),
            () -> new Message("pn", "waba", "910000000001", "Hi", -1),
            () -> new Message("pn", "waba", "910000000001", "Hi", 721)
        }) {
            assertThatThrownBy(bad::run).isInstanceOf(LocalToolException.class);
        }
    }

    @Test
    void anApiThatDoesNotAnswerIsReportedPlainly() {
        assertThatThrownBy(() -> tool.postTwice(
                        URI.create("http://127.0.0.1:1/webhooks/whatsapp"),
                        new Message("pn", "waba", "910000000001", "Hi", 0),
                        secret()))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("unreachable"));
    }

    // Helpers

    private record Business(UUID tenant, String phoneNumberId) {}

    /** A business with a number and the gate's development workflow, so a message opens a lead. */
    private Business business() {
        UUID tenant = data.tenant();
        seed.seed(tenant);
        return new Business(tenant, data.whatsAppNumber(tenant));
    }

    private static Message message(Business business, String text, int hoursAgo) {
        return message(
                business,
                text,
                hoursAgo,
                "910000%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)));
    }

    private static Message message(Business business, String text, int hoursAgo, String from) {
        return new Message(business.phoneNumberId(), "waba-gate", from, text, hoursAgo);
    }

    private URI webhook() {
        return URI.create("http://localhost:" + port + "/webhooks/whatsapp");
    }

    private byte[] secret() {
        return secrets.signer().secret().getBytes(StandardCharsets.UTF_8);
    }

    private long events(Answers answers) {
        return admin.sql("select count(*) from northstar.webhook_events where provider_event_id = :e")
                .param("e", "message:" + answers.wamid())
                .query(Long.class)
                .single();
    }

    /** Processes the stored message as the worker does. */
    private void process(Business business, Answers answers) {
        UUID row = admin.sql("select id from northstar.webhook_events where provider_event_id = :e")
                .param("e", "message:" + answers.wamid())
                .query(UUID.class)
                .single();
        RequestAttributes bound = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            inboundJob.run(new ProcessWhatsAppWebhook(business.tenant(), row));
        } finally {
            if (bound != null) RequestContextHolder.setRequestAttributes(bound);
        }
    }

    /** contacts|open leads in new|conversations|messages of the synthetic sender */
    private String rows(Business business, String phone) {
        return admin.sql("""
                        select count(distinct ct.id) || '|' || count(distinct l.id) || '|' || count(distinct c.id) || '|'
                               || count(distinct m.id)
                        from northstar.contacts ct
                        left join northstar.leads l
                          on l.contact_id = ct.id and l.stage = 'new' and l.closed_at is null and l.origin = 'direct'
                        left join northstar.conversations c on c.contact_id = ct.id
                        left join northstar.messages m on m.conversation_id = c.id
                        where ct.tenant_id = :t and ct.normalized_phone = :p""")
                .param("t", business.tenant())
                .param("p", phone)
                .query(String.class)
                .single();
    }
}
