package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.devtools.QueueAutomatedText.Answer;
import com.northstar.devtools.QueueAutomatedText.Result;
import com.northstar.integrations.WhatsAppMessages;
import com.northstar.tenancy.TenantDirectory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Phase 2 gate's automated send (step 2.11): queued for an ordinary conversation, refused while a staff member has
 * taken it over, and cancelled for a contact who opted out (W32), through the same public service the engine will use.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class QueueAutomatedTextTests {

    private final QueueAutomatedText tool;
    private final TestData data;
    private final JdbcClient admin;

    QueueAutomatedTextTests(
            @Autowired WhatsAppMessages messages,
            @Autowired TenantDirectory tenants,
            @Autowired PostgresAdmin postgres) {
        this.tool = new QueueAutomatedText(messages, tenants);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void itQueuesOneSystemMessageForAnOrdinaryConversation() {
        UUID tenant = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);

        Answer answer = tool.queue(tenant, conversation, "Automated hello");

        assertThat(answer.result()).isEqualTo(Result.QUEUED);
        assertThat(state(answer)).isEqualTo("system|queued|none|pending|none");
        assertThat(tool.queue(tenant, conversation, "Automated hello").messageId())
                .as("each run is its own send")
                .isNotEqualTo(answer.messageId());
    }

    @Test
    void itIsRefusedWhileAStaffMemberHasTakenTheConversationOverAndStoresNothing() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);
        admin.sql("""
                        update northstar.conversations
                        set automation_paused = true, taken_over_by_membership_id = :m, taken_over_at = now()
                        where id = :id""")
                .param("m", owner.membershipId())
                .param("id", conversation)
                .update();

        Answer answer = tool.queue(tenant, conversation, "Automated hello");

        assertThat(answer).isEqualTo(new Answer(Result.REFUSED_AUTOMATION_PAUSED, null, null));
        assertThat(count("northstar.messages", "conversation_id", conversation)).isZero();
        assertThat(count("northstar.actions", "tenant_id", tenant)).isZero();

        admin.sql("""
                        update northstar.conversations
                        set automation_paused = false, taken_over_by_membership_id = null, taken_over_at = null
                        where id = :id""").param("id", conversation).update();
        assertThat(tool.queue(tenant, conversation, "Automated hello").result())
                .as("handed back")
                .isEqualTo(Result.QUEUED);
    }

    @Test
    void W32_itIsCancelledForAContactWhoOptedOutAndItsMessageFails() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        UUID conversation = data.conversation(tenant, contact, null);
        admin.sql("""
                        update northstar.contacts
                        set consent_state = 'opted_out', opt_out_at = now(), last_opt_out_at = now() where id = :id""").param("id", contact).update();

        Answer answer = tool.queue(tenant, conversation, "Automated hello");

        assertThat(answer.result()).isEqualTo(Result.CANCELLED_CONTACT_OPTED_OUT);
        assertThat(state(answer)).isEqualTo("system|failed|contact_opted_out|cancelled|contact_opted_out");
    }

    @Test
    void P9_anotherBusinessesConversationAndAMissingOneAreTheSame() {
        UUID tenant = data.tenant();
        UUID other = data.tenant();
        UUID theirs = data.conversation(other, data.contact(other), null);

        for (UUID conversation : new UUID[] {theirs, UUID.randomUUID()}) {
            assertThatThrownBy(() -> tool.queue(tenant, conversation, "Automated hello"))
                    .isInstanceOfSatisfying(
                            LocalToolException.class, e -> assertThat(e.code()).isEqualTo("conversation_not_found"));
        }
        assertThatThrownBy(() -> tool.queue(UUID.randomUUID(), theirs, "Automated hello"))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("unknown_tenant"));
        assertThat(count("northstar.messages", "conversation_id", theirs)).isZero();
    }

    /** sender|message status|message error|action status|cancel reason */
    private String state(Answer answer) {
        return admin.sql("""
                        select m.sender_type || '|' || m.status || '|' || coalesce(m.error_code, 'none') || '|' || a.status
                               || '|' || coalesce(a.cancel_reason, 'none')
                        from northstar.messages m join northstar.actions a on a.id = :a
                        where m.id = :m""")
                .param("a", answer.actionId())
                .param("m", answer.messageId())
                .query(String.class)
                .single();
    }

    private long count(String table, String column, UUID id) {
        return admin.sql("select count(*) from " + table + " where " + column + " = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }
}
