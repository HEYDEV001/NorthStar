package com.northstar.contacts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.NotFoundException;
import com.northstar.contacts.MessageToSend;
import com.northstar.contacts.OutboundContent;
import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The outbound message writer (step 2.6a) on its own: a message is marked sent or failed only while it is still queued
 * without a provider ID, so the two never overwrite each other and Meta's ID is set once; the send's read carries the
 * template as it is now and the window with the margin; another tenant's records are not there (P9); and only the system
 * actor, inside a transaction, may call it (P10).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboundMessagesTests {

    private static final Duration MARGIN = Duration.ofMinutes(2);

    private final OutboundMessages outbound;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    OutboundMessagesTests(
            @Autowired OutboundMessages outbound,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired PostgresAdmin postgres) {
        this.outbound = outbound;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @BeforeEach
    void runLikeAWorkerThread() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sentAndFailedNeverOverwriteEachOther() {
        Conversation conversation = conversation();
        UUID sent = queueText(conversation, "Will be sent");
        UUID failed = queueText(conversation, "Will fail");

        assertThat(inTransaction(conversation.tenant(), () -> outbound.markSent(sent, "wamid.ONE")))
                .isTrue();
        assertThat(inTransaction(conversation.tenant(), () -> outbound.markSent(sent, "wamid.TWO")))
                .as("Meta's ID is set once")
                .isFalse();
        assertThat(inTransaction(conversation.tenant(), () -> outbound.markFailed(sent, "timeout")))
                .as("a message Meta accepted is never marked failed")
                .isFalse();
        assertThat(inTransaction(conversation.tenant(), () -> outbound.markFailed(failed, "window_closed_no_template")))
                .isTrue();
        assertThat(inTransaction(conversation.tenant(), () -> outbound.markSent(failed, "wamid.LATE")))
                .as("a failed message is never marked sent")
                .isFalse();

        assertThat(state(sent)).isEqualTo("sent||wamid.ONE");
        assertThat(state(failed)).isEqualTo("failed|window_closed_no_template|");
    }

    @Test
    void theSendsReadCarriesTheTemplateAsItIsNowAndTheWindowWithTheMargin() {
        Conversation conversation = conversation();
        UUID template = data.messageTemplate(conversation.tenant());
        UUID message = inTransaction(
                        conversation.tenant(),
                        () -> outbound.queue(
                                UUID.randomUUID(),
                                conversation.id(),
                                OutboundMessages.Sender.AI,
                                new OutboundContent.Template(
                                        template, TemplateParameters.named(Map.of("first_name", "Asha")))))
                .messageId();
        admin.sql("update northstar.message_templates set status = 'paused' where id = :id")
                .param("id", template)
                .update();
        setWindowLeft(conversation, "1 minute");

        MessageToSend read = inTransaction(conversation.tenant(), () -> outbound.toSend(message, MARGIN))
                .orElseThrow();

        assertThat(read.type()).isEqualTo("template");
        assertThat(read.isQueued()).isTrue();
        assertThat(read.accepted()).isFalse();
        assertThat(read.text()).isNull();
        assertThat(read.template().status()).as("read when the send runs").isEqualTo("paused");
        assertThat(read.template().language()).isEqualTo("en");
        assertThat(read.template().parameters()).isEqualTo(TemplateParameters.named(Map.of("first_name", "Asha")));
        assertThat(read.windowOpen()).as("one minute left is inside the margin").isFalse();
        assertThat(read.recipientPhone()).isEqualTo(conversation.phone());
        assertThat(read.toString()).doesNotContain(conversation.phone(), "Asha");

        setWindowLeft(conversation, "3 minutes");
        assertThat(inTransaction(conversation.tenant(), () -> outbound.toSend(message, MARGIN))
                        .orElseThrow()
                        .windowOpen())
                .isTrue();
    }

    @Test
    void P9_anotherTenantsMessagesAndConversationsAreNotThere() {
        Conversation ours = conversation();
        Conversation theirs = conversation();
        UUID theirMessage = queueText(theirs, "Theirs");

        Optional<MessageToSend> read = inTransaction(ours.tenant(), () -> outbound.toSend(theirMessage, MARGIN));
        boolean failed = inTransaction(ours.tenant(), () -> outbound.markFailed(theirMessage, "timeout"));
        boolean sent = inTransaction(ours.tenant(), () -> outbound.markSent(theirMessage, "wamid.FORGED"));

        assertThat(read).isEmpty();
        assertThat(failed).isFalse();
        assertThat(sent).isFalse();
        assertThat(state(theirMessage)).isEqualTo("queued||");
        assertThatThrownBy(() -> inTransaction(
                        ours.tenant(),
                        () -> outbound.queue(
                                UUID.randomUUID(),
                                theirs.id(),
                                OutboundMessages.Sender.SYSTEM,
                                new OutboundContent.Text("Into their conversation"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void P10_onlyTheSystemActorInsideATransactionMayWrite() {
        Conversation conversation = conversation();
        UUID message = queueText(conversation, "Queued");
        TestData.Member member = data.member("admin");

        assertThatThrownBy(() -> outbound.markFailed(message, "timeout"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactions.execute(status -> outbound.markFailed(message, "timeout")))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                        () -> transactions.execute(status -> outbound.markFailed(message, "timeout"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system actor");

        assertThat(state(message)).isEqualTo("queued||");
    }

    // Helpers.

    private record Conversation(UUID tenant, UUID id, String phone) {}

    private Conversation conversation() {
        UUID tenant = data.tenant();
        String phone = "+91" + (9_000_000_000L + (long) (Math.random() * 999_999_999L));
        UUID contact = data.contact(tenant, phone, null);
        return new Conversation(tenant, data.conversation(tenant, contact, data.whatsAppNumber(tenant), null), phone);
    }

    private UUID queueText(Conversation conversation, String text) {
        return inTransaction(
                        conversation.tenant(),
                        () -> outbound.queue(
                                UUID.randomUUID(),
                                conversation.id(),
                                OutboundMessages.Sender.SYSTEM,
                                new OutboundContent.Text(text)))
                .messageId();
    }

    private void setWindowLeft(Conversation conversation, String left) {
        admin.sql("update northstar.conversations set last_inbound_at = now() - interval '24 hours' + cast(:left as"
                        + " interval) where id = :id")
                .param("left", left)
                .param("id", conversation.id())
                .update();
    }

    private <T> T inTransaction(UUID tenant, Supplier<T> work) {
        return SystemScope.call(tenant, () -> transactions.execute(status -> work.get()));
    }

    /** status|error_code|provider_message_id */
    private String state(UUID message) {
        return admin.sql("""
                        select status || '|' || coalesce(error_code, '') || '|' || coalesce(provider_message_id, '')
                        from northstar.messages where id = :id""").param("id", message).query(String.class).single();
    }
}
