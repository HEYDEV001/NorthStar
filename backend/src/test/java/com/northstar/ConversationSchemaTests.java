package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The conversation tables' rules in the database (step 2.3, data-model.md sections 4, 6, 12 and 13): every reference
 * stays inside its tenant and a lead's conversation is its contact's own, one conversation per contact and channel, the
 * 24-hour window, one stored message per WhatsApp message ID (P1), set-once columns, the column checks, each table's
 * column grants, and what app_admin may not read.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConversationSchemaTests {

    private final PostgresAdmin postgres;
    private final JdbcClient admin;
    private final AppUserDatabase app;
    private final TestData data;

    ConversationSchemaTests(@Autowired PostgresAdmin postgres, @Autowired AppUserDatabase app) {
        this.postgres = postgres;
        this.admin = postgres.jdbc();
        this.app = app;
        this.data = new TestData(postgres);
    }

    @Test
    void everyReferenceStaysInsideItsTenant() {
        TestData.Member owner = data.member("admin");
        TestData.Member outsider = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID contact = data.contact(tenant);
        String number = data.whatsAppNumber(tenant);
        UUID conversation = data.conversation(tenant, contact, owner.membershipId());
        UUID foreignConversation = data.conversation(outsider.tenantId(), data.contact(outsider.tenantId()), null);
        UUID foreignTemplate = data.messageTemplate(outsider.tenantId());

        for (String[] foreign : new String[][] {
            {"contact_id", data.contact(outsider.tenantId()).toString()},
            {"whatsapp_phone_number_id", data.whatsAppNumber(outsider.tenantId())},
            {"assigned_membership_id", outsider.membershipId().toString()}
        }) {
            // A fresh contact each time, so the one-conversation-per-contact key is not what refuses it.
            UUID freshContact = data.contact(tenant);
            assertThatThrownBy(() -> admin.sql("""
                                    insert into northstar.conversations
                                      (tenant_id, contact_id, channel, whatsapp_phone_number_id, assigned_membership_id)
                                    values (:t, :contact, 'whatsapp', :number, :member)""")
                            .param("t", tenant)
                            .param(
                                    "contact",
                                    foreign[0].equals("contact_id") ? UUID.fromString(foreign[1]) : freshContact)
                            .param("number", foreign[0].equals("whatsapp_phone_number_id") ? foreign[1] : number)
                            .param(
                                    "member",
                                    foreign[0].equals("assigned_membership_id") ? UUID.fromString(foreign[1]) : null)
                            .update())
                    .as("a conversation with another tenant's " + foreign[0])
                    .rootCause()
                    .hasMessageContaining("foreign key");
        }
        assertThatThrownBy(() -> insertInbound(tenant, foreignConversation, "wamid.TEST" + UUID.randomUUID()))
                .as("a message in another tenant's conversation")
                .rootCause()
                .hasMessageContaining("foreign key");
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.messages
                                  (tenant_id, conversation_id, direction, sender_type, sender_membership_id, type,
                                   status)
                                values (:t, :c, 'out', 'staff', :member, 'text', 'queued')""")
                        .param("t", tenant)
                        .param("c", conversation)
                        .param("member", outsider.membershipId())
                        .update())
                .as("a message sent by another tenant's member")
                .rootCause()
                .hasMessageContaining("foreign key");
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.messages
                                  (tenant_id, conversation_id, direction, sender_type, type, template_id, status)
                                values (:t, :c, 'out', 'system', 'template', :template, 'queued')""")
                        .param("t", tenant)
                        .param("c", conversation)
                        .param("template", foreignTemplate)
                        .update())
                .as("a message using another tenant's template")
                .rootCause()
                .hasMessageContaining("foreign key");
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.contact_sources (tenant_id, contact_id, source)
                                values (:t, :contact, 'direct')""")
                        .param("t", tenant)
                        .param("contact", data.contact(outsider.tenantId()))
                        .update())
                .as("a source of another tenant's contact")
                .rootCause()
                .hasMessageContaining("foreign key");
    }

    @Test
    void oneConversationPerContactAndChannel() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        data.conversation(tenant, contact, null);

        assertThatThrownBy(() -> data.conversation(tenant, contact, null))
                .rootCause()
                .hasMessageContaining("conversations_one_per_contact_and_channel");
        for (String[] invalid : new String[][] {
            {"channel = 'sms'", "conversations_channel_check"},
            {"status = 'archived'", "conversations_status_check"}
        }) {
            assertThatThrownBy(() -> admin.sql(
                                    "update northstar.conversations set " + invalid[0] + " where contact_id = :c")
                            .param("c", contact)
                            .update())
                    .as(invalid[0])
                    .rootCause()
                    .hasMessageContaining(invalid[1]);
        }
    }

    @Test
    void windowExpiresTwentyFourHoursAfterTheLastInboundMessage() {
        UUID tenant = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);

        // In a session whose time zone changes its clocks inside the window (London leaves summer time on 25 October
        // 2026), the window still lasts exactly 24 hours: 11:00 UTC to 11:00 UTC, not to 12:00 as "a day" would.
        String window = postgres.rolledBack(jdbc -> {
            jdbc.sql("set local time zone 'Europe/London'").update();
            jdbc.sql("update northstar.conversations set last_inbound_at = '2026-10-24 12:00:00+01' where id = :id")
                    .param("id", conversation)
                    .update();
            return jdbc.sql("""
                            select extract(epoch from window_expires_at - last_inbound_at)::bigint || ' '
                                   || (window_expires_at = timestamptz '2026-10-25 11:00:00+00')
                            from northstar.conversations where id = :id""").param("id", conversation).query(String.class).single();
        });
        assertThat(window).isEqualTo("86400 true");

        UUID quiet = data.conversation(tenant, data.contact(tenant), null);
        admin.sql("update northstar.conversations set last_inbound_at = null where id = :id")
                .param("id", quiet)
                .update();
        assertThat(admin.sql("select window_expires_at is null from northstar.conversations where id = :id")
                        .param("id", quiet)
                        .query(Boolean.class)
                        .single())
                .as("no inbound message, no window")
                .isTrue();

        // Generated: nobody writes it, not even the table's owner.
        assertThatThrownBy(() -> postgres.rolledBack(jdbc -> {
                    jdbc.sql("set local role migrator").update();
                    jdbc.sql("update northstar.conversations set window_expires_at = now() where id = :id")
                            .param("id", conversation)
                            .update();
                }))
                .rootCause()
                .hasMessageContaining("window_expires_at");
    }

    @Test
    void aLeadsConversationMustBeItsContactsOwn() {
        TestData.Member owner = data.member("admin");
        TestData.Member outsider = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID lead = data.lead(tenant, data.workflowVersion(tenant), null);
        UUID contact = admin.sql("select contact_id from northstar.leads where id = :id")
                .param("id", lead)
                .query(UUID.class)
                .single();
        UUID own = data.conversation(tenant, contact, null);
        UUID anotherContacts = data.conversation(tenant, data.contact(tenant), null);
        UUID anotherTenants = data.conversation(outsider.tenantId(), data.contact(outsider.tenantId()), null);

        assertThat(linkLead(lead, own)).isOne();
        assertThatThrownBy(() -> linkLead(lead, anotherContacts))
                .as("another contact's conversation in the same business")
                .rootCause()
                .hasMessageContaining("leads_conversation_fk");
        assertThatThrownBy(() -> linkLead(lead, anotherTenants))
                .as("another business's conversation")
                .rootCause()
                .hasMessageContaining("leads_conversation_fk");
        assertThatThrownBy(() -> linkLead(lead, UUID.randomUUID()))
                .rootCause()
                .hasMessageContaining("leads_conversation_fk");
        assertThat(linkLead(lead, null)).as("a lead without a conversation").isOne();
    }

    @Test
    void P1_aMessageIsStoredOncePerProviderMessageId() {
        UUID tenant = data.tenant();
        UUID other = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);
        UUID otherConversation = data.conversation(other, data.contact(other), null);
        String wamid = "wamid.TEST" + UUID.randomUUID();
        String insertOnce = """
                insert into northstar.messages
                  (tenant_id, conversation_id, direction, sender_type, provider_message_id, type, body)
                values (:t, :c, 'in', 'contact', :wamid, 'text', 'Hi')
                on conflict (tenant_id, provider_message_id) do nothing""";

        // As the worker stores it: the system actor through app_user, with RLS.
        int first = asSystem(
                tenant,
                jdbc -> jdbc.sql(insertOnce)
                        .param("t", tenant)
                        .param("c", conversation)
                        .param("wamid", wamid)
                        .update());
        int again = asSystem(
                tenant,
                jdbc -> jdbc.sql(insertOnce)
                        .param("t", tenant)
                        .param("c", conversation)
                        .param("wamid", wamid)
                        .update());

        assertThat(first).isOne();
        assertThat(again).as("a redelivered message is not stored twice").isZero();
        assertThatThrownBy(() -> insertInbound(tenant, conversation, wamid))
                .rootCause()
                .hasMessageContaining("messages_provider_message_key");
        assertThat(insertInbound(other, otherConversation, wamid))
                .as("the key is per tenant")
                .isNotNull();
        // Outbound messages are stored as queued before WhatsApp returns their ID: several may wait at once.
        for (int i = 0; i < 2; i++) {
            assertThat(insertQueued(tenant, conversation)).isNotNull();
        }
        assertThat(admin.sql("select count(*) from northstar.messages where provider_message_id = :wamid")
                        .param("wamid", wamid)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void providerIdAndMediaPathAreSetOnce() {
        UUID tenant = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);
        UUID message = insertQueued(tenant, conversation);
        String wamid = "wamid.TEST" + UUID.randomUUID();
        String path = tenant + "/media/" + UUID.randomUUID() + ".jpg";

        assertThat(updateAsSystem(tenant, message, "provider_message_id = '" + wamid + "', status = 'sent'"))
                .isOne();
        assertThat(updateAsSystem(tenant, message, "media_path = '" + path + "'"))
                .isOne();
        assertThat(updateAsSystem(
                        tenant,
                        message,
                        "provider_message_id = '" + wamid + "', media_path = '" + path + "', status = 'delivered'"))
                .as("writing the same values again")
                .isOne();

        for (String change : List.of(
                "provider_message_id = 'wamid.other'",
                "provider_message_id = null",
                "media_path = '" + tenant + "/media/other.jpg'",
                "media_path = null")) {
            assertThatThrownBy(() -> updateAsSystem(tenant, message, change))
                    .as(change)
                    .satisfies(refused -> assertThat(sqlState(refused)).isEqualTo("NSM01"));
            // The trigger holds for the table's owner too, which has every privilege and bypasses RLS.
            assertThatThrownBy(() -> postgres.rolledBack(jdbc -> {
                        jdbc.sql("set local role migrator").update();
                        jdbc.sql("update northstar.messages set " + change + " where id = :id")
                                .param("id", message)
                                .update();
                    }))
                    .as("as migrator: " + change)
                    .satisfies(refused -> assertThat(sqlState(refused)).isEqualTo("NSM01"));
        }
        assertThat(admin.sql("select provider_message_id || ' ' || media_path from northstar.messages where id = :id")
                        .param("id", message)
                        .query(String.class)
                        .single())
                .isEqualTo(wamid + " " + path);
    }

    @Test
    void messageColumnsAreChecked() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);
        UUID template = data.messageTemplate(tenant);
        UUID inbound = insertInbound(tenant, conversation, "wamid.TEST" + UUID.randomUUID());
        UUID outbound = insertQueued(tenant, conversation);

        for (String[] invalid : new String[][] {
            {"sender_type = 'ai'", "messages_inbound_from_contact"},
            {"status = 'sent'", "messages_status_outbound_only"},
            {"type = 'template', template_id = '" + template + "'", "messages_template"},
            {"type = 'sticker'", "messages_type_check"}
        }) {
            assertThatThrownBy(() -> updateAsAdmin(inbound, invalid[0]))
                    .as("inbound: " + invalid[0])
                    .rootCause()
                    .hasMessageContaining(invalid[1]);
        }
        for (String[] invalid : new String[][] {
            {"sender_type = 'contact'", "messages_inbound_from_contact"},
            {"sender_type = 'staff'", "messages_staff_sender"},
            {"sender_membership_id = '" + owner.membershipId() + "'", "messages_staff_sender"},
            {"type = 'template'", "messages_template"},
            {"template_id = '" + template + "'", "messages_template"},
            {"status = null", "messages_status_outbound_only"},
            {"status = 'bounced', provider_message_id = 'wamid.TEST-bounced'", "messages_status_check"},
            {"status = 'sent'", "messages_provider_id_required"},
            {"error_code = 'Template paused by Meta'", "messages_error_code_check"},
            {"media_path = '" + UUID.randomUUID() + "/media/x.jpg'", "messages_media_in_tenant_folder"},
            {"media_path = '" + tenant + "/'", "messages_media_in_tenant_folder"},
            {"media_path = '" + tenant + "/../" + UUID.randomUUID() + "/x.jpg'", "messages_media_in_tenant_folder"},
            {"media_path = 'media/" + tenant + "/x.jpg'", "messages_media_in_tenant_folder"}
        }) {
            assertThatThrownBy(() -> updateAsAdmin(outbound, invalid[0]))
                    .as("outbound: " + invalid[0])
                    .rootCause()
                    .hasMessageContaining(invalid[1]);
        }
        assertThatThrownBy(() -> insertInbound(tenant, conversation, null))
                .as("an inbound message always has WhatsApp's ID")
                .rootCause()
                .hasMessageContaining("messages_provider_id_required");

        // Valid: a staff reply, a template, a failed send with no ID, media in the tenant's folder, and a message
        // whose text was removed (retention clears body and keeps the row).
        assertThat(updateAsAdmin(
                        outbound, "sender_type = 'staff', sender_membership_id = '" + owner.membershipId() + "'"))
                .isOne();
        assertThat(updateAsAdmin(outbound, "type = 'template', template_id = '" + template + "'"))
                .isOne();
        assertThat(updateAsAdmin(outbound, "status = 'failed', error_code = 'template_paused'"))
                .isOne();
        assertThat(updateAsAdmin(outbound, "media_path = '" + tenant + "/media/" + UUID.randomUUID() + ".jpg'"))
                .isOne();
        assertThat(updateAsAdmin(inbound, "body = null")).isOne();
    }

    @Test
    void statusOnlyMovesForwardForEveryRole() {
        UUID tenant = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);
        List<String> statuses = List.of("queued", "sent", "delivered", "read", "failed");
        List<String> order = List.of("queued", "sent", "delivered", "read");

        // Step 2.7: queued < sent < delivered < read, any jump forward; failed replaces queued or sent only, and
        // nothing leaves failed. The same status again is allowed.
        for (String from : statuses) {
            for (String to : statuses) {
                boolean allowed = from.equals(to)
                        || (!from.equals("failed") && !to.equals("failed") && order.indexOf(to) > order.indexOf(from))
                        || (to.equals("failed") && (from.equals("queued") || from.equals("sent")));
                UUID message = outboundWithStatus(tenant, conversation, from);
                String move = from + " -> " + to;
                // A message without WhatsApp's ID gets one with the move, so the ID check is not what answers.
                boolean needsId = !to.equals("queued") && !to.equals("failed");
                boolean hasId = !from.equals("queued") && !from.equals("failed");
                String set = "status = '" + to + "'"
                        + (needsId && !hasId ? ", provider_message_id = 'wamid.TEST" + UUID.randomUUID() + "'" : "");
                if (allowed) {
                    assertThat(updateAsSystem(tenant, message, set)).as(move).isOne();
                } else {
                    assertThatThrownBy(() -> updateAsSystem(tenant, message, set))
                            .as(move)
                            .satisfies(refused -> assertThat(sqlState(refused)).isEqualTo("NSM02"));
                    // The table's owner too, which has every privilege and bypasses RLS.
                    assertThatThrownBy(() -> postgres.rolledBack(jdbc -> {
                                jdbc.sql("set local role migrator").update();
                                jdbc.sql("update northstar.messages set " + set + " where id = :id")
                                        .param("id", message)
                                        .update();
                            }))
                            .as("as migrator: " + move)
                            .satisfies(refused -> assertThat(sqlState(refused)).isEqualTo("NSM02"));
                }
            }
        }
    }

    @Test
    void deliveryAndReadTimesAreSetOnce() {
        UUID tenant = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);
        UUID message = outboundWithStatus(tenant, conversation, "sent");

        assertThat(updateAsSystem(tenant, message, "status = 'delivered', delivered_at = '2026-11-21T10:00:00Z'"))
                .isOne();
        assertThat(updateAsSystem(tenant, message, "status = 'read', read_at = '2026-11-21T10:01:00Z'"))
                .isOne();
        assertThat(updateAsSystem(tenant, message, "delivered_at = delivered_at, read_at = read_at"))
                .as("the same value again is no change")
                .isOne();
        for (String change : List.of(
                "delivered_at = '2026-11-21T11:00:00Z'",
                "delivered_at = null",
                "read_at = '2026-11-21T11:00:00Z'",
                "read_at = null")) {
            assertThatThrownBy(() -> updateAsSystem(tenant, message, change))
                    .as(change)
                    .satisfies(refused -> assertThat(sqlState(refused)).isEqualTo("NSM01"));
        }
    }

    @Test
    void templateParamsAreATemplateMessagesStringValuesOnly() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);
        UUID template = data.messageTemplate(tenant);
        UUID text = insertQueued(tenant, conversation);
        UUID templated = insertQueued(tenant, conversation);
        assertThat(updateAsAdmin(templated, "type = 'template', template_id = '" + template + "'"))
                .isOne();

        // Step 2.6a: positional values (an array of strings) or named values (an object of strings), at most 8 KB,
        // on a template message only.
        for (String invalid : List.of(
                "'[\"Asha\", 3]'",
                "'[[\"Asha\"]]'",
                "'{\"first_name\": {\"x\": \"Asha\"}}'",
                "'{\"first_name\": null}'",
                "'\"Asha\"'",
                "'42'",
                "to_jsonb(array[repeat('x', 8200)])")) {
            assertThatThrownBy(() -> updateAsAdmin(templated, "template_params = " + invalid))
                    .as(invalid)
                    .rootCause()
                    .hasMessageContaining("messages_template_params");
        }
        assertThatThrownBy(() -> updateAsAdmin(text, "template_params = '[\"Asha\"]'"))
                .as("only a template message has template values")
                .rootCause()
                .hasMessageContaining("messages_template_params");
        for (String valid : List.of("'[]'", "'[\"Asha\", \"Saturday\"]'", "'{\"first_name\": \"Asha\"}'", "null")) {
            assertThat(updateAsAdmin(templated, "template_params = " + valid))
                    .as(valid)
                    .isOne();
        }

        // Written once, like body: inserted by the app, never updated by it, and never read by app_admin.
        assertThat(appUserColumns("northstar.messages", "INSERT")).contains("template_params");
        assertThat(appUserColumns("northstar.messages", "UPDATE")).doesNotContain("template_params");
        assertThat(appAdminCanSelect("northstar.messages", "template_params")).isFalse();
    }

    @Test
    void templatesAreUniquePerNameAndLanguage() {
        UUID tenant = data.tenant();
        UUID other = data.tenant();

        insertTemplate(tenant, "booking_reminder", "en");
        assertThatThrownBy(() -> insertTemplate(tenant, "booking_reminder", "en"))
                .rootCause()
                .hasMessageContaining("message_templates_name_language_key");
        insertTemplate(tenant, "booking_reminder", "hi");
        insertTemplate(other, "booking_reminder", "en");
        assertThatThrownBy(() -> admin.sql(
                                "update northstar.message_templates set status = 'flagged' where tenant_id = :t")
                        .param("t", tenant)
                        .update())
                .rootCause()
                .hasMessageContaining("message_templates_status_check");
        assertThatThrownBy(() -> insertTemplate(tenant, "", "en"))
                .rootCause()
                .hasMessageContaining("message_templates_name_check");
    }

    @Test
    void templatesTakeMetasStatusesOneRowPerMetaIdAndVariablesOfOneShape() {
        UUID tenant = data.tenant();
        UUID other = data.tenant();
        insertTemplate(tenant, "status_probe", "en");
        for (String status :
                List.of("draft", "submitted", "approved", "rejected", "paused", "disabled", "deleted", "archived")) {
            assertThat(admin.sql("update northstar.message_templates set status = :s where tenant_id = :t")
                            .param("s", status)
                            .param("t", tenant)
                            .update())
                    .as(status)
                    .isOne();
        }

        insertTemplate(tenant, "id_probe_one", "en");
        insertTemplate(tenant, "id_probe_two", "en");
        insertTemplate(other, "id_probe_one", "en");
        String setId = "update northstar.message_templates set provider_template_id = '1387372356726668'"
                + " where tenant_id = :t and name = :name";
        admin.sql(setId).param("t", tenant).param("name", "id_probe_one").update();
        assertThatThrownBy(() -> admin.sql(setId)
                        .param("t", tenant)
                        .param("name", "id_probe_two")
                        .update())
                .as("one row per Meta template in a tenant")
                .rootCause()
                .hasMessageContaining("message_templates_provider_template_key");
        assertThat(admin.sql(setId)
                        .param("t", other)
                        .param("name", "id_probe_one")
                        .update())
                .as("the key is per tenant")
                .isOne();

        String setVariables = "update northstar.message_templates set variables = cast(:v as jsonb)"
                + " where tenant_id = :t and name = 'status_probe'";
        for (String valid : List.of(
                "{\"format\": \"positional\", \"names\": [\"1\", \"2\"]}", "{\"format\": \"named\", \"names\": []}")) {
            assertThat(admin.sql(setVariables)
                            .param("v", valid)
                            .param("t", tenant)
                            .update())
                    .as(valid)
                    .isOne();
        }
        for (String invalid :
                List.of("[\"first_name\"]", "{\"format\": \"other\", \"names\": []}", "{\"format\": \"named\"}")) {
            assertThatThrownBy(() -> admin.sql(setVariables)
                            .param("v", invalid)
                            .param("t", tenant)
                            .update())
                    .as(invalid)
                    .rootCause()
                    .hasMessageContaining("message_templates_variables_shape");
        }
    }

    @Test
    void W34_aTemplatesQueuedMessagesAreFoundByAnIndexOfQueuedMessagesOnly() {
        assertThat(admin.sql("""
                        select indexdef from pg_indexes
                        where schemaname = 'northstar' and indexname = 'messages_queued_by_template'""").query(String.class).single())
                .as("step 2.8c: a template's status change finds its sends by their queued messages")
                .contains("ON northstar.messages")
                .contains("(tenant_id, template_id)")
                .contains("WHERE (status = 'queued'::text)");
    }

    @Test
    void theInboxListsConversationsByTheirLatestActivityThroughItsTwoIndexes() {
        assertThat(admin.sql("""
                        select is_nullable || '|' || column_default from information_schema.columns
                        where table_schema = 'northstar' and table_name = 'conversations'
                          and column_name = 'last_message_at'""").query(String.class).single())
                .as("step 2.9a: every conversation has a latest activity, the database's time")
                .isEqualTo("NO|now()");
        assertThat(admin.sql("""
                        select indexname || ' ' || indexdef from pg_indexes
                        where schemaname = 'northstar' and tablename = 'conversations'
                          and indexname in ('conversations_inbox', 'conversations_inbox_assigned')
                        order by indexname""").query(String.class).list())
                .satisfiesExactly(
                        inbox -> assertThat(inbox).contains("(tenant_id, last_message_at DESC, id DESC)"),
                        assigned -> assertThat(assigned)
                                .contains("(tenant_id, assigned_membership_id, last_message_at DESC, id DESC)"));

        UUID tenant = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), data.whatsAppNumber(tenant), null);
        assertThat(admin.sql("select last_message_at > now() - interval '1 minute' from northstar.conversations"
                                + " where id = :id")
                        .param("id", conversation)
                        .query(Boolean.class)
                        .single())
                .as("a new conversation starts active now")
                .isTrue();
    }

    @Test
    void aTakeOverNamesItsTenantsMemberAndItsTimeTogetherAndAlwaysPausesAutomation() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID conversation = data.conversation(tenant, data.contact(tenant), data.whatsAppNumber(tenant), null);
        UUID foreignMember = data.member("admin").membershipId();

        for (String[] invalid : List.of(
                new String[] {"automation_paused = true, taken_over_at = now()", "conversations_taken_over_complete"},
                new String[] {
                    "automation_paused = true, taken_over_by_membership_id = '" + owner.membershipId() + "'",
                    "conversations_taken_over_complete"
                },
                new String[] {
                    "taken_over_by_membership_id = '" + owner.membershipId() + "', taken_over_at = now()",
                    "conversations_taken_over_is_paused"
                },
                new String[] {
                    "automation_paused = true, taken_over_by_membership_id = '" + foreignMember
                            + "', taken_over_at = now()",
                    "conversations_taken_over_by_fk"
                })) {
            assertThatThrownBy(() -> admin.sql("update northstar.conversations set " + invalid[0] + " where id = :id")
                            .param("id", conversation)
                            .update())
                    .as(invalid[0])
                    .rootCause()
                    .hasMessageContaining(invalid[1]);
        }
        assertThat(admin.sql("""
                        update northstar.conversations
                        set automation_paused = true, taken_over_by_membership_id = :m, taken_over_at = now()
                        where id = :id""")
                        .param("m", owner.membershipId())
                        .param("id", conversation)
                        .update())
                .isOne();
        assertThat(admin.sql("""
                        select string_agg(column_name, ',' order by column_name) from information_schema.columns
                        where table_schema = 'northstar' and table_name = 'conversations'
                          and column_name in ('ai_paused', 'automation_paused')""").query(String.class).single())
                .as("step 2.9c: ai_paused is now automation_paused")
                .isEqualTo("automation_paused");
    }

    @Test
    void contactSourcesAreRecordedOncePerSource() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        UUID link = UUID.randomUUID();
        assertThat(recordSource(tenant, contact, "direct", null)).isOne();
        assertThat(recordSource(tenant, contact, "direct", null))
                .as("the same source again, its empty link included")
                .isZero();
        assertThat(recordSource(tenant, contact, "chat_link", link)).isOne();
        assertThat(recordSource(tenant, contact, "chat_link", link)).isZero();
        assertThat(recordSource(tenant, contact, "chat_link", UUID.randomUUID()))
                .as("another chat link is another source")
                .isOne();
        assertThatThrownBy(() -> recordSource(tenant, contact, "billboard", null))
                .rootCause()
                .hasMessageContaining("contact_sources_source_check");
    }

    @Test
    void columnGrantsMatchEachTablesTier() {
        assertThat(appUserColumns("northstar.conversations", "UPDATE"))
                .containsExactlyInAnyOrder(
                        "status",
                        "automation_paused",
                        "taken_over_by_membership_id",
                        "taken_over_at",
                        "needs_human",
                        "assigned_membership_id",
                        "last_inbound_at",
                        "last_message_at",
                        "last_contact_message_at");
        assertThat(appUserColumns("northstar.messages", "UPDATE"))
                .containsExactlyInAnyOrder(
                        "status",
                        "error_code",
                        "sent_at",
                        "delivered_at",
                        "read_at",
                        "provider_message_id",
                        "media_path");
        assertThat(appUserColumns("northstar.message_templates", "UPDATE"))
                .containsExactlyInAnyOrder(
                        "body",
                        "variables",
                        "category",
                        "provider_template_id",
                        "status",
                        "approved_at",
                        "meta_updated_at");
        assertThat(appUserColumns("northstar.contact_sources", "UPDATE")).isEmpty();

        // Inserts name every column but the database's own: created_at, updated_at and the generated window.
        assertThat(appUserColumns("northstar.conversations", "INSERT"))
                .doesNotContain("created_at", "updated_at", "window_expires_at")
                .contains("contact_id", "whatsapp_phone_number_id", "last_inbound_at");
        assertThat(appUserColumns("northstar.messages", "INSERT"))
                .doesNotContain("created_at", "updated_at")
                .contains("body", "provider_message_id", "sent_at");
        assertThat(appUserColumns("northstar.message_templates", "INSERT"))
                .doesNotContain("created_at", "updated_at")
                .contains("name", "language", "status", "meta_updated_at");
        assertThat(appUserColumns("northstar.contact_sources", "INSERT"))
                .contains("contact_id", "source", "first_seen_at");
        for (String table : List.of(
                "northstar.conversations",
                "northstar.messages",
                "northstar.message_templates",
                "northstar.contact_sources")) {
            assertThat(admin.sql("""
                                    select p from unnest(array['DELETE', 'TRUNCATE']) p
                                    where has_table_privilege('app_user', cast(:table as regclass), p)""").param("table", table).query(String.class).list())
                    .as(table + ": nothing is deleted")
                    .isEmpty();
        }

        // And as the app meets them: an admin of the business, through app_user with RLS.
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID contact = data.contact(tenant);
        UUID conversation = data.conversation(tenant, contact, null);
        UUID message = data.message(tenant, conversation);
        for (String statement : List.of(
                "update northstar.messages set body = 'rewritten' where id = '" + message + "'",
                "update northstar.messages set direction = 'out' where id = '" + message + "'",
                "update northstar.conversations set contact_id = '" + data.contact(tenant) + "' where id = '"
                        + conversation + "'",
                "update northstar.conversations set whatsapp_phone_number_id = 'pn-other' where id = '" + conversation
                        + "'",
                "update northstar.message_templates set name = 'renamed' where tenant_id = '" + tenant + "'",
                "update northstar.contact_sources set source = 'manual' where tenant_id = '" + tenant + "'",
                "delete from northstar.messages where id = '" + message + "'",
                "delete from northstar.conversations where id = '" + conversation + "'")) {
            assertThatThrownBy(() -> app.run(
                            tenant,
                            owner.userId(),
                            "admin",
                            jdbc -> jdbc.sql(statement).update()))
                    .as(statement)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void appAdminCannotReadMessageBodies() {
        assertThat(appAdminCanSelect("northstar.messages", "body")).isFalse();
        assertThat(appAdminCanSelect("northstar.messages", "template_params")).isFalse();
        assertThat(appAdminColumnsWithout("northstar.messages", "body"))
                .as("every other column but a template's values (step 2.6a) stays readable for the admin panel")
                .containsExactly("template_params");
        assertThat(appAdminSelectRefused("select body from northstar.messages limit 1"))
                .contains("permission denied");
        assertThat(appAdminSelectRefused("select template_params from northstar.messages limit 1"))
                .contains("permission denied");
        assertThat(appAdminSelectRefused("select id, status, created_at from northstar.messages limit 1"))
                .isNull();
    }

    @Test
    void appAdminCannotReadWebhookPayloads() {
        assertThat(appAdminCanSelect("northstar.webhook_events", "payload")).isFalse();
        assertThat(appAdminColumnsWithout("northstar.webhook_events", "payload"))
                .as("every other column stays readable for the admin panel")
                .isEmpty();
        assertThat(appAdminSelectRefused("select payload from northstar.webhook_events limit 1"))
                .contains("permission denied");
        assertThat(appAdminSelectRefused("select * from northstar.webhook_events limit 1"))
                .contains("permission denied");
        assertThat(appAdminSelectRefused("select id, provider_event_id, error from northstar.webhook_events limit 1"))
                .isNull();
    }

    // Helpers.

    private UUID insertInbound(UUID tenant, UUID conversation, String wamid) {
        return admin.sql("""
                        insert into northstar.messages
                          (tenant_id, conversation_id, direction, sender_type, provider_message_id, type, body)
                        values (:t, :c, 'in', 'contact', :wamid, 'text', 'Hi') returning id""")
                .param("t", tenant)
                .param("c", conversation)
                .param("wamid", wamid)
                .query(UUID.class)
                .single();
    }

    /** An outbound message with this status: an ID from WhatsApp unless it is queued or failed before sending. */
    private UUID outboundWithStatus(UUID tenant, UUID conversation, String status) {
        boolean withId = !status.equals("queued") && !status.equals("failed");
        return admin.sql("""
                        insert into northstar.messages
                          (tenant_id, conversation_id, direction, sender_type, provider_message_id, type, body, status)
                        values (:t, :c, 'out', 'system', :wamid, 'text', 'Thanks!', :status) returning id""")
                .param("t", tenant)
                .param("c", conversation)
                .param("wamid", withId ? "wamid.TEST" + UUID.randomUUID() : null)
                .param("status", status)
                .query(UUID.class)
                .single();
    }

    /** An AI reply queued for sending, before WhatsApp has returned its ID. */
    private UUID insertQueued(UUID tenant, UUID conversation) {
        return admin.sql("""
                        insert into northstar.messages
                          (tenant_id, conversation_id, direction, sender_type, type, body, status)
                        values (:t, :c, 'out', 'ai', 'text', 'Thanks!', 'queued') returning id""")
                .param("t", tenant)
                .param("c", conversation)
                .query(UUID.class)
                .single();
    }

    private void insertTemplate(UUID tenant, String name, String language) {
        admin.sql("insert into northstar.message_templates (tenant_id, name, language) values (:t, :name, :language)")
                .param("t", tenant)
                .param("name", name)
                .param("language", language)
                .update();
    }

    /** Records a source as the inbound job will: the system actor, once per distinct source. */
    private int recordSource(UUID tenant, UUID contact, String source, UUID chatLink) {
        Integer inserted = asSystem(
                tenant,
                jdbc -> jdbc.sql("""
                        insert into northstar.contact_sources (tenant_id, contact_id, source, chat_link_id)
                        values (:t, :contact, :source, :link)
                        on conflict on constraint contact_sources_one_per_source do nothing""")
                        .param("t", tenant)
                        .param("contact", contact)
                        .param("source", source)
                        .param("link", chatLink)
                        .update());
        return inserted;
    }

    private int linkLead(UUID lead, UUID conversation) {
        return admin.sql("update northstar.leads set conversation_id = :c where id = :id")
                .param("c", conversation)
                .param("id", lead)
                .update();
    }

    private int updateAsAdmin(UUID message, String set) {
        return admin.sql("update northstar.messages set " + set + " where id = :id")
                .param("id", message)
                .update();
    }

    private int updateAsSystem(UUID tenant, UUID message, String set) {
        return asSystem(
                tenant,
                jdbc -> jdbc.sql("update northstar.messages set " + set + " where id = :id")
                        .param("id", message)
                        .update());
    }

    private <T> T asSystem(UUID tenant, java.util.function.Function<JdbcClient, T> work) {
        return app.as(tenant, TenantContext.SYSTEM_USER_ID, "system", work);
    }

    private List<String> appUserColumns(String table, String privilege) {
        return admin.sql("""
                        select attname from pg_attribute
                        where attrelid = cast(:table as regclass) and attnum > 0 and not attisdropped
                          and has_column_privilege('app_user', attrelid, attname, :privilege)""")
                .param("table", table)
                .param("privilege", privilege)
                .query(String.class)
                .list();
    }

    private boolean appAdminCanSelect(String table, String column) {
        return admin.sql("""
                        select has_table_privilege('app_admin', cast(:table as regclass), 'SELECT')
                               or has_column_privilege('app_admin', cast(:table as regclass), :column, 'SELECT')""")
                .param("table", table)
                .param("column", column)
                .query(Boolean.class)
                .single();
    }

    /** The table's columns, other than the hidden one, that app_admin cannot select (none expected). */
    private List<String> appAdminColumnsWithout(String table, String hidden) {
        return admin.sql("""
                        select attname from pg_attribute
                        where attrelid = cast(:table as regclass) and attnum > 0 and not attisdropped
                          and attname <> :hidden
                          and not has_column_privilege('app_admin', attrelid, attname, 'SELECT')""")
                .param("table", table)
                .param("hidden", hidden)
                .query(String.class)
                .list();
    }

    /**
     * Runs the select as app_admin, which has no login in tests: postgres takes on the role inside a transaction that
     * is rolled back. Null when it succeeds, else the refusal.
     */
    private String appAdminSelectRefused(String select) {
        try {
            postgres.rolledBack(jdbc -> {
                jdbc.sql("grant app_admin to postgres").update();
                jdbc.sql("set local role app_admin").update();
                jdbc.sql(select).query().listOfRows();
            });
            return null;
        } catch (RuntimeException refused) {
            Throwable root = refused;
            while (root.getCause() != null) root = root.getCause();
            return String.valueOf(root.getMessage());
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }
}
