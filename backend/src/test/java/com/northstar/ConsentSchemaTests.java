package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The database side of opt-out (step 2.5, W32; data-model.md sections 3, 4 and 6), through the real app_user login with
 * RLS: the action recipient and cancel reason, the backstop that refuses a runnable action for an opted-out contact
 * (NSA01), the guard that keeps an opt-out final and lets only the system change consent (NSC01), and the tenant's own
 * keywords.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConsentSchemaTests {

    private static final UUID NIL_USER = TenantContext.SYSTEM_USER_ID;

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;

    ConsentSchemaTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    // The backstop on actions (NSA01)

    @Test
    void W32_theDatabaseRefusesARunnableActionForAnOptedOutContact() {
        UUID tenant = data.tenant();
        UUID optedOut = data.contact(tenant);
        optOut(optedOut);

        for (String status : List.of("pending", "needs_review", "processing", "paused")) {
            assertThatThrownBy(() -> asSystem(tenant, jdbc -> insertAction(jdbc, tenant, optedOut, status, null)))
                    .as("inserting a " + status + " action")
                    .rootCause()
                    .hasMessageContaining("opted out");
        }
        assertThatThrownBy(() -> insertAction(admin, tenant, optedOut, "pending", null))
                .as("for every role, the table's owner and superusers included")
                .rootCause()
                .hasMessageContaining("opted out");
        asSystem(
                tenant,
                jdbc -> assertThat(insertAction(jdbc, tenant, optedOut, "cancelled", "contact_opted_out"))
                        .as("recording a refused action is allowed")
                        .isNotNull());
        asSystem(
                tenant,
                jdbc -> assertThat(insertAction(jdbc, tenant, null, "pending", null))
                        .as("an action with no recipient is never checked")
                        .isNotNull());
    }

    @Test
    void W32_claimingAnActionIsRefusedOnceItsRecipientOptedOutButCancellingItIsNot() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        UUID claimable = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        UUID cancellable = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        UUID inFlight = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        asSystem(tenant, jdbc -> claim(jdbc, inFlight));
        optOut(contact);

        assertThatThrownBy(() -> asSystem(tenant, jdbc -> claim(jdbc, claimable)))
                .rootCause()
                .hasMessageContaining("opted out")
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSA01"));
        assertThatThrownBy(() -> asSystem(
                        tenant, jdbc -> jdbc.sql("""
                                        update northstar.actions set attempts = attempts + 1,
                                          lease_expires_at = now() + interval '5 minutes'
                                        where id = :id""").param("id", inFlight).update()))
                .as("taking over a lost attempt is a new claim")
                .rootCause()
                .hasMessageContaining("opted out");
        asSystem(
                tenant,
                jdbc -> assertThat(jdbc.sql("""
                                update northstar.actions
                                set status = 'cancelled', cancel_reason = 'contact_opted_out', next_attempt_at = null
                                where id = :id""").param("id", cancellable).update())
                        .isOne());
        asSystem(
                tenant,
                jdbc -> assertThat(jdbc.sql("""
                                update northstar.actions set status = 'pending', lease_expires_at = null,
                                  next_attempt_at = now() + interval '30 seconds',
                                  last_error_class = 'retryable', last_error_code = 'http_503'
                                where id = :id""").param("id", inFlight).update())
                        .as("an attempt in flight can still record its outcome; its next claim is refused")
                        .isOne());
    }

    @Test
    void W32_aStaffMembersActionForAContactTheyCannotSeeIsRefused() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        data.membership(tenant, staffUser, "staff", "active");
        UUID unassigned = data.contact(tenant);

        assertThatThrownBy(() -> app.as(
                        tenant, staffUser, "staff", jdbc -> insertAction(jdbc, tenant, unassigned, "pending", null)))
                .as("fails closed")
                .rootCause()
                .hasMessageContaining("not visible");
    }

    // Step 2.9b: the one exception, a staff member's text reply after the client wrote again (NSA01)

    @Test
    void W32_theDatabaseLetsThroughOnlyAStaffTextReplyInTheWindowTheClientOpenedAfterOptingOut() {
        Reply reply = replyAfterOptOut();
        UUID tenant = reply.tenant();

        asSystem(
                tenant,
                jdbc -> assertThat(insertSend(jdbc, tenant, reply.contact(), reply.staffText(), "pending"))
                        .as("a staff member's text inside the window")
                        .isNotNull());
        assertThat(insertSend(admin, tenant, reply.contact(), reply.staffText(), "pending"))
                .as("for every role")
                .isNotNull();
        for (UUID refused : List.of(reply.staffTemplate(), reply.systemText(), reply.aiText(), reply.elsewhere())) {
            assertThatThrownBy(() ->
                            asSystem(tenant, jdbc -> insertSend(jdbc, tenant, reply.contact(), refused, "pending")))
                    .as("a template, a system or AI text, or a staff text in another contact's conversation")
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSA01"));
            assertThatThrownBy(() -> insertSend(admin, tenant, reply.contact(), refused, "pending"))
                    .as("for every role, the owner included")
                    .rootCause()
                    .hasMessageContaining("opted out");
        }
        for (String payload : List.of("{}", "{\"messageId\": \"not-a-uuid\"}", "[\"" + reply.staffText() + "\"]")) {
            assertThatThrownBy(() -> asSystem(
                            tenant,
                            jdbc -> insertAction(jdbc, tenant, reply.contact(), "whatsapp.send", payload, "pending")))
                    .as(payload)
                    .rootCause()
                    .hasMessageContaining("opted out");
        }
        assertThatThrownBy(() -> asSystem(
                        tenant,
                        jdbc -> insertAction(
                                jdbc,
                                tenant,
                                reply.contact(),
                                "test.fake.contact",
                                "{\"messageId\": \"" + reply.staffText() + "\"}",
                                "pending")))
                .as("another type naming the staff text")
                .rootCause()
                .hasMessageContaining("opted out");
        assertThat(this.<Boolean>asSystem(
                        tenant,
                        jdbc -> jdbc.sql("""
                                select northstar.opt_out_reply_until(:t, :c)
                                  = (select last_contact_message_at + interval '24 hours'
                                     from northstar.conversations where id = :c)""")
                                .param("t", tenant)
                                .param("c", reply.conversation())
                                .query(Boolean.class)
                                .single()))
                .isTrue();
    }

    @Test
    void W32_theExceptionClosesWithTheWindowAnotherOptOutOrAMessageInTheSameSecond() {
        for (String close : List.of(
                "update northstar.conversations set last_contact_message_at = now() - interval '24 hours'"
                        + " - interval '1 second' where id = :conversation",
                "update northstar.contacts set last_opt_out_at = now() where id = :contact",
                "update northstar.conversations set last_contact_message_at = (select last_opt_out_at from"
                        + " northstar.contacts where id = :contact) where id = :conversation")) {
            Reply reply = replyAfterOptOut();
            UUID claimable = asSystem(
                    reply.tenant(),
                    jdbc -> insertSend(jdbc, reply.tenant(), reply.contact(), reply.staffText(), "pending"));
            admin.sql(close)
                    .param("conversation", reply.conversation())
                    .param("contact", reply.contact())
                    .update();

            assertThatThrownBy(() -> asSystem(reply.tenant(), jdbc -> claim(jdbc, claimable)))
                    .as(close)
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSA01"));
            assertThat(this.<Boolean>asSystem(
                            reply.tenant(),
                            jdbc -> jdbc.sql("select coalesce(northstar.opt_out_reply_until(:t, :c) <= now(), true)")
                                    .param("t", reply.tenant())
                                    .param("c", reply.conversation())
                                    .query(Boolean.class)
                                    .single()))
                    .as("closed (no end, or an end already past): " + close)
                    .isTrue();
        }
        Reply open = replyAfterOptOut();
        UUID claimable = asSystem(
                open.tenant(), jdbc -> insertSend(jdbc, open.tenant(), open.contact(), open.staffText(), "pending"));
        assertThat(this.<Integer>asSystem(open.tenant(), jdbc -> claim(jdbc, claimable)))
                .as("claimed while the exception is open")
                .isOne();
        assertThat(this.<String>asSystem(
                        open.tenant(),
                        jdbc -> jdbc.sql("""
                                select northstar.staff_reply_allowed_after_opt_out(:t, :c, :m, interval '0')
                                  || '|' || northstar.staff_reply_allowed_after_opt_out(:t, :c, :m, interval '1 day')""")
                                .param("t", open.tenant())
                                .param("c", open.contact())
                                .param("m", open.staffText())
                                .query(String.class)
                                .single()))
                .as("the margin narrows the window")
                .isEqualTo("true|false");
    }

    // Step 2.9b: the latest opt-out (NSC01) and when the client wrote (NSV01)

    @Test
    void W32_theLatestOptOutIsTheSystemsAndOnlyMovesForward() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        asSystem(
                tenant,
                jdbc -> updateContact(
                        jdbc,
                        contact,
                        "consent_state = 'opted_out', opt_out_at = now() - interval '1 hour',"
                                + " last_opt_out_at = now() - interval '1 hour'"));

        for (String change : List.of("last_opt_out_at = now() - interval '2 hours'", "last_opt_out_at = null")) {
            for (JdbcClientRunner who :
                    List.<JdbcClientRunner>of(work -> asSystem(tenant, work), work -> work.apply(admin))) {
                assertThatThrownBy(() -> who.run(jdbc -> updateContact(jdbc, contact, change)))
                        .as(change)
                        .rootCause()
                        .satisfies(e ->
                                assertThat(((SQLException) e).getSQLState()).isEqualTo("NSC01"));
            }
        }
        TestData.Member owner = data.member("admin");
        UUID ownersContact = data.contact(owner.tenantId());
        admin.sql("""
                        update northstar.contacts set consent_state = 'opted_out', opt_out_at = now(),
                          last_opt_out_at = now() where id = :id""").param("id", ownersContact).update();
        assertThatThrownBy(() -> app.as(
                        owner.tenantId(),
                        owner.userId(),
                        "admin",
                        jdbc -> updateContact(jdbc, ownersContact, "last_opt_out_at = now() + interval '1 minute'")))
                .as("an admin")
                .rootCause()
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSC01"));
        assertThat(this.<Integer>asSystem(tenant, jdbc -> updateContact(jdbc, contact, "last_opt_out_at = now()")))
                .as("the system moves it forward")
                .isOne();

        UUID fresh = data.contact(tenant);
        assertThatThrownBy(() -> updateContact(admin, fresh, "consent_state = 'opted_out', opt_out_at = now()"))
                .rootCause()
                .hasMessageContaining("contacts_last_opt_out_complete");
        assertThatThrownBy(() -> updateContact(
                        admin,
                        fresh,
                        "consent_state = 'opted_out', opt_out_at = now(),"
                                + " last_opt_out_at = now() - interval '1 minute'"))
                .rootCause()
                .hasMessageContaining("contacts_last_opt_out_after_first");
    }

    @Test
    void W32_whenTheClientWroteIsTheSystemsToRecordAndOnlyMovesForward() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        UUID staff = data.membership(tenant, staffUser, "staff", "active");
        UUID conversation = data.conversation(tenant, data.contact(tenant), data.whatsAppNumber(tenant), staff);

        for (String column : List.of("last_inbound_at", "last_contact_message_at")) {
            String set = "update northstar.conversations set " + column + " = now() where id = :id";
            assertThatThrownBy(() -> app.as(
                            tenant,
                            owner.userId(),
                            "admin",
                            jdbc -> jdbc.sql(set).param("id", conversation).update()))
                    .as("an admin: " + column)
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSV01"));
            assertThatThrownBy(() -> app.as(
                            tenant,
                            staffUser,
                            "staff",
                            jdbc -> jdbc.sql(set).param("id", conversation).update()))
                    .as("the assigned staff member: " + column)
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSV01"));
            assertThat(this.<Integer>asSystem(
                            tenant,
                            jdbc -> jdbc.sql(set).param("id", conversation).update()))
                    .as("the system: " + column)
                    .isOne();
            for (String back : List.of("now() - interval '1 hour'", "null")) {
                assertThatThrownBy(() -> asSystem(
                                tenant,
                                jdbc -> jdbc.sql("update northstar.conversations set " + column + " = " + back
                                                + " where id = :id")
                                        .param("id", conversation)
                                        .update()))
                        .as("the system moving " + column + " to " + back)
                        .rootCause()
                        .satisfies(e ->
                                assertThat(((SQLException) e).getSQLState()).isEqualTo("NSV01"));
            }
            assertThat(admin.sql("update northstar.conversations set " + column + " = now() - interval '1 day'"
                                    + " where id = :id")
                            .param("id", conversation)
                            .update())
                    .as("an operator, as fixtures and repairs do")
                    .isOne();
        }
        assertThat(app.<Integer>as(
                        tenant,
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("update northstar.conversations set needs_human = true where id = :id")
                                .param("id", conversation)
                                .update()))
                .as("other columns are unaffected")
                .isOne();
        assertThatThrownBy(() -> app.as(
                        tenant,
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("""
                                insert into northstar.conversations
                                  (tenant_id, contact_id, channel, whatsapp_phone_number_id, last_inbound_at)
                                values (:t, :c, 'whatsapp', :n, now())""")
                                .param("t", tenant)
                                .param("c", data.contact(tenant))
                                .param("n", data.whatsAppNumber(tenant))
                                .update()))
                .as("inserting with a time")
                .rootCause()
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSV01"));
        assertThat(admin.sql("""
                        select string_agg(privilege_type, ',' order by privilege_type)
                        from information_schema.column_privileges
                        where table_schema = 'northstar' and table_name = 'conversations'
                          and column_name = 'last_contact_message_at' and grantee = 'app_user'""").query(String.class).single()).isEqualTo("SELECT,UPDATE");
    }

    // The recipient and the cancel reason

    @Test
    void anActionsRecipientIsItsTenantsOwnContactAndWrittenOnce() {
        UUID tenant = data.tenant();
        UUID theirContact = data.contact(data.tenant());
        // As cancelled, which the backstop lets through, so the foreign key itself is what refuses it.
        assertThatThrownBy(() -> insertAction(admin, tenant, theirContact, "cancelled", "contact_opted_out"))
                .rootCause()
                .hasMessageContaining("actions_recipient_fk");
        assertThatThrownBy(() -> insertAction(admin, tenant, theirContact, "pending", null))
                .as("and a runnable one is refused before it gets there")
                .rootCause()
                .hasMessageContaining("not visible");

        assertThat(appUserColumns("INSERT")).contains("recipient_contact_id", "cancel_reason");
        assertThat(appUserColumns("UPDATE")).contains("cancel_reason").doesNotContain("recipient_contact_id");
        UUID contact = data.contact(tenant);
        UUID action = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        assertThatThrownBy(() -> asSystem(
                        tenant,
                        jdbc -> jdbc.sql("update northstar.actions set recipient_contact_id = null where id = :id")
                                .param("id", action)
                                .update()))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThat(admin.sql("""
                        select indexdef from pg_indexes
                        where schemaname = 'northstar' and indexname = 'actions_open_by_recipient'""").query(String.class).single())
                .contains("(tenant_id, recipient_contact_id)")
                .contains("needs_review");
    }

    @Test
    void aCancelReasonIsAFixedCodeSetExactlyWhenTheActionIsCancelled() {
        UUID tenant = data.tenant();
        for (String[] bad : new String[][] {
            {"cancelled", null}, {"pending", "contact_opted_out"}, {"cancelled", "Contact opted out"}, {"cancelled", ""}
        }) {
            assertThatThrownBy(() -> insertAction(admin, tenant, null, bad[0], bad[1]))
                    .as(bad[0] + " with " + bad[1])
                    .rootCause()
                    .hasMessageContaining("check constraint");
        }
    }

    // The consent guard on contacts (NSC01)

    @Test
    void W32_anOptOutIsFinalAndItsTimeIsSetOnceForEveryRole() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        asSystem(
                tenant,
                jdbc -> assertThat(jdbc.sql("""
                                update northstar.contacts set consent_state = 'opted_out', opt_out_at = now(), last_opt_out_at = now()
                                where id = :id""").param("id", contact).update()).isOne());

        for (String change : List.of(
                "consent_state = 'unknown'",
                "consent_state = 'opted_in', opted_in_at = now()",
                "opt_out_at = now() + interval '1 day'",
                "opt_out_at = null")) {
            assertThatThrownBy(() -> asSystem(tenant, jdbc -> updateContact(jdbc, contact, change)))
                    .as("the system: " + change)
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSC01"));
            assertThatThrownBy(() -> updateContact(admin, contact, change))
                    .as("an operator: " + change)
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSC01"));
        }
        asSystem(
                tenant,
                jdbc -> assertThat(updateContact(jdbc, contact, "name = 'Renamed'"))
                        .as("the rest of the contact can still change")
                        .isOne());
        assertThat(admin.sql("select consent_state from northstar.contacts where id = :id")
                        .param("id", contact)
                        .query(String.class)
                        .single())
                .isEqualTo("opted_out");
    }

    @Test
    void W32_onlyTheSystemChangesAContactsConsent() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID contact = data.contact(tenant);

        for (String change : List.of(
                "consent_state = 'opted_out', opt_out_at = now(), last_opt_out_at = now()",
                "consent_state = 'opted_in', opted_in_at = now()",
                "opted_in_at = now()")) {
            assertThatThrownBy(() ->
                            app.run(tenant, owner.userId(), "admin", jdbc -> updateContact(jdbc, contact, change)))
                    .as("an admin: " + change)
                    .rootCause()
                    .hasMessageContaining("Only the system");
        }
        assertThatThrownBy(() -> app.run(
                        tenant,
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("""
                                        insert into northstar.contacts (tenant_id, normalized_phone, consent_state, opted_in_at)
                                        values (:t, '+919999900001', 'opted_in', now())""").param("t", tenant).update()))
                .as("nor can a new contact arrive with consent recorded")
                .rootCause()
                .hasMessageContaining("Only the system");
        app.run(
                tenant,
                owner.userId(),
                "admin",
                jdbc -> assertThat(updateContact(jdbc, contact, "name = 'Edited'"))
                        .as("an admin still edits the contact's other columns")
                        .isOne());
        asSystem(
                tenant,
                jdbc -> assertThat(updateContact(jdbc, contact, "consent_state = 'opted_in', opted_in_at = now()"))
                        .isOne());
    }

    @Test
    void anOptedOutContactHasItsOptOutTime() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        assertThatThrownBy(() -> updateContact(admin, contact, "consent_state = 'opted_out', last_opt_out_at = now()"))
                .rootCause()
                .hasMessageContaining("contacts_opted_out_has_time");
    }

    // The tenant's keywords

    @Test
    void W32_aTenantsKeywordsAreCheckedAndNotWritableByTheAppYet() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        assertThat(admin.sql("select opt_out_keywords::text from northstar.tenants where id = :t")
                        .param("t", tenant)
                        .query(String.class)
                        .single())
                .as("none by default")
                .isEqualTo("{}");

        assertThatThrownBy(() -> admin.sql("""
                                update northstar.tenants
                                set opt_out_keywords = array(select 'word ' || g from generate_series(1, 21) g)
                                where id = :t""").param("t", tenant).update())
                .rootCause()
                .hasMessageContaining("tenants_opt_out_keywords_valid");
        assertThatThrownBy(() -> admin.sql(
                                "update northstar.tenants set opt_out_keywords = array['stop', null] where id = :t")
                        .param("t", tenant)
                        .update())
                .rootCause()
                .hasMessageContaining("tenants_opt_out_keywords_valid");

        assertThatThrownBy(() -> app.run(
                        tenant,
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("update northstar.tenants set opt_out_keywords = array['halt'] where id = :t")
                                .param("t", tenant)
                                .update()))
                .as("the settings step adds the write path")
                .rootCause()
                .hasMessageContaining("permission denied");
        admin.sql("update northstar.tenants set opt_out_keywords = array['halt'] where id = :t")
                .param("t", tenant)
                .update();
        String seen = asSystem(
                tenant,
                jdbc -> jdbc.sql("select opt_out_keywords::text from northstar.tenants")
                        .query(String.class)
                        .single());
        assertThat(seen).as("the system reads its own tenant's keywords").isEqualTo("{halt}");
    }

    // Helpers

    private <T> T asSystem(UUID tenant, Function<JdbcClient, T> work) {
        return app.as(tenant, NIL_USER, "system", work);
    }

    private void optOut(UUID contact) {
        admin.sql(
                        "update northstar.contacts set consent_state = 'opted_out', opt_out_at = now(), last_opt_out_at = now() where id = :id")
                .param("id", contact)
                .update();
    }

    private static UUID insertAction(JdbcClient jdbc, UUID tenant, UUID recipient, String status, String reason) {
        return jdbc.sql("""
                        insert into northstar.actions
                          (tenant_id, type, status, idempotency_key, mode, next_attempt_at, recipient_contact_id,
                           cancel_reason)
                        values (:t, 'test.fake.contact', :status, :key, 'automatic',
                                case when :status = 'pending' then now() end, :recipient, :reason)
                        returning id""")
                .param("t", tenant)
                .param("status", status)
                .param("key", "schema:" + UUID.randomUUID())
                .param("recipient", recipient)
                .param("reason", reason)
                .query(UUID.class)
                .single();
    }

    /** Runs work through one login or another, so a test can check several with the same steps. */
    @FunctionalInterface
    private interface JdbcClientRunner {
        int run(Function<JdbcClient, Integer> work);
    }

    /**
     * An opted-out contact (an hour ago) who wrote again ten minutes ago, in a conversation assigned to a staff member,
     * with queued outbound messages of every kind: the staff member's text and template, a system and an AI text, and
     * a staff text in another contact's conversation.
     */
    private record Reply(
            UUID tenant,
            UUID contact,
            UUID conversation,
            UUID staffText,
            UUID staffTemplate,
            UUID systemText,
            UUID aiText,
            UUID elsewhere) {}

    private Reply replyAfterOptOut() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staff = data.membership(tenant, data.user(), "staff", "active");
        String number = data.whatsAppNumber(tenant);
        UUID contact = data.contact(tenant);
        UUID conversation = data.conversation(tenant, contact, number, staff);
        UUID otherConversation = data.conversation(tenant, data.contact(tenant), number, staff);
        admin.sql("""
                        update northstar.contacts set consent_state = 'opted_out', opt_out_at = now() - interval '1 hour',
                          last_opt_out_at = now() - interval '1 hour' where id = :id""").param("id", contact).update();
        admin.sql("""
                        update northstar.conversations
                        set last_inbound_at = now() - interval '10 minutes',
                            last_contact_message_at = now() - interval '10 minutes'
                        where id in (:a, :b)""").param("a", conversation).param("b", otherConversation).update();
        UUID template = data.messageTemplate(tenant);
        return new Reply(
                tenant,
                contact,
                conversation,
                outbound(tenant, conversation, "staff", staff, "text", null),
                outbound(tenant, conversation, "staff", staff, "template", template),
                outbound(tenant, conversation, "system", null, "text", null),
                outbound(tenant, conversation, "ai", null, "text", null),
                outbound(tenant, otherConversation, "staff", staff, "text", null));
    }

    private UUID outbound(UUID tenant, UUID conversation, String sender, UUID membership, String type, UUID template) {
        return admin.sql("""
                        insert into northstar.messages
                          (tenant_id, conversation_id, direction, sender_type, sender_membership_id, type, body,
                           template_id, status)
                        values (:t, :c, 'out', :sender, :membership, :type, 'Reply', :template, 'queued')
                        returning id""")
                .param("t", tenant)
                .param("c", conversation)
                .param("sender", sender)
                .param("membership", membership)
                .param("type", type)
                .param("template", template)
                .query(UUID.class)
                .single();
    }

    /** A whatsapp.send action for this message, as WhatsAppMessages creates one (its payload the message ID only). */
    private static UUID insertSend(JdbcClient jdbc, UUID tenant, UUID recipient, UUID message, String status) {
        return insertAction(jdbc, tenant, recipient, "whatsapp.send", "{\"messageId\": \"" + message + "\"}", status);
    }

    private static UUID insertAction(
            JdbcClient jdbc, UUID tenant, UUID recipient, String type, String payload, String status) {
        return jdbc.sql("""
                        insert into northstar.actions
                          (tenant_id, type, status, idempotency_key, mode, payload, next_attempt_at,
                           recipient_contact_id)
                        values (:t, :type, :status, :key, 'automatic', cast(:payload as jsonb),
                                case when :status = 'pending' then now() end, :recipient)
                        returning id""")
                .param("t", tenant)
                .param("type", type)
                .param("status", status)
                .param("key", "schema:" + UUID.randomUUID())
                .param("payload", payload)
                .param("recipient", recipient)
                .query(UUID.class)
                .single();
    }

    /** A claim as the executor makes it: processing under a lease. */
    private static int claim(JdbcClient jdbc, UUID action) {
        return jdbc.sql("""
                        update northstar.actions set status = 'processing', attempts = attempts + 1,
                          next_attempt_at = null, last_attempt_at = now(), lease_expires_at = now() + interval '5 minutes'
                        where id = :id""").param("id", action).update();
    }

    private static int updateContact(JdbcClient jdbc, UUID contact, String assignments) {
        return jdbc.sql("update northstar.contacts set " + assignments + " where id = :id")
                .param("id", contact)
                .update();
    }

    private List<String> appUserColumns(String privilege) {
        return admin.sql("""
                        select a.attname from pg_attribute a
                        where a.attrelid = 'northstar.actions'::regclass and a.attnum > 0 and not a.attisdropped
                          and has_column_privilege('app_user', a.attrelid, a.attnum, :p)""").param("p", privilege).query(String.class).list();
    }
}
