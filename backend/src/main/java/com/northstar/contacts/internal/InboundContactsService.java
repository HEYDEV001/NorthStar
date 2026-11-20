package com.northstar.contacts.internal;

import com.northstar.contacts.ContactSource;
import com.northstar.contacts.InboundContacts;
import com.northstar.contacts.InboundMessage;
import com.northstar.contacts.PhoneNumbers;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InboundContacts} (step 2.4b). Native SQL for the upserts and row locks (D7), with bind parameters and an
 * explicit tenant filter on top of RLS. MANDATORY: each call joins the inbound job's transaction and none opens its
 * own, so a failure anywhere in the job leaves nothing behind (P3).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class InboundContactsService implements InboundContacts {

    private static final String MESSAGE_STORED = """
            select exists (
              select from northstar.messages where tenant_id = ? and provider_message_id = ?)""";

    // A new contact is created with the profile name; an existing one is locked, and its name filled only if empty.
    private static final String INSERT_CONTACT = """
            insert into northstar.contacts (tenant_id, normalized_phone, name)
            values (?, ?, ?)
            on conflict (tenant_id, normalized_phone) do nothing""";

    private static final String LOCK_CONTACT = """
            select id from northstar.contacts
            where tenant_id = ? and normalized_phone = ?
            for update""";

    private static final String FILL_EMPTY_NAME = """
            update northstar.contacts set name = ?
            where tenant_id = ? and id = ? and (name is null or btrim(name) = '')""";

    private static final String INSERT_SOURCE = """
            insert into northstar.contact_sources (tenant_id, contact_id, source, first_seen_at)
            values (?, ?, ?, ?)
            on conflict on constraint contact_sources_one_per_source do nothing""";

    private static final String INSERT_CONVERSATION = """
            insert into northstar.conversations
              (tenant_id, contact_id, channel, whatsapp_phone_number_id, assigned_membership_id)
            values (?, ?, 'whatsapp', ?, ?)
            on conflict on constraint conversations_one_per_contact_and_channel do nothing""";

    private static final String LOCK_CONVERSATION = """
            select id, assigned_membership_id from northstar.conversations
            where tenant_id = ? and contact_id = ? and channel = 'whatsapp'
            for update""";

    // greatest(last_inbound_at, ?), written only when it moves forward, so an older message changes nothing.
    private static final String RECORD_INBOUND_AT = """
            update northstar.conversations set last_inbound_at = ?
            where tenant_id = ? and id = ? and (last_inbound_at is null or last_inbound_at < ?)""";

    private static final String INSERT_MESSAGE = """
            insert into northstar.messages
              (tenant_id, conversation_id, direction, sender_type, provider_message_id, type, body, sent_at)
            values (?, ?, 'in', 'contact', ?, ?, ?, ?)
            on conflict (tenant_id, provider_message_id) do nothing
            returning id""";

    private final JdbcClient jdbc;

    InboundContactsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasInboundMessage(String providerMessageId) {
        Objects.requireNonNull(providerMessageId, "providerMessageId");
        return Boolean.TRUE.equals(jdbc.sql(MESSAGE_STORED)
                .param(systemTenant())
                .param(providerMessageId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public UUID lockContact(String normalizedPhone, String profileName) {
        if (!PhoneNumbers.isE164(normalizedPhone)) throw new IllegalArgumentException("Not an E.164 phone number");
        UUID tenant = systemTenant();
        String name = cleanName(profileName);
        jdbc.sql(INSERT_CONTACT)
                .param(tenant)
                .param(normalizedPhone)
                .param(name)
                .update();
        UUID contact = jdbc.sql(LOCK_CONTACT)
                .param(tenant)
                .param(normalizedPhone)
                .query(UUID.class)
                .single();
        if (name != null)
            jdbc.sql(FILL_EMPTY_NAME).param(name).param(tenant).param(contact).update();
        return contact;
    }

    @Override
    public void recordSource(UUID contactId, ContactSource source, Instant firstSeenAt) {
        Objects.requireNonNull(contactId, "contactId");
        Objects.requireNonNull(source, "source");
        jdbc.sql(INSERT_SOURCE)
                .param(systemTenant())
                .param(contactId)
                .param(source.code())
                .param(utc(firstSeenAt))
                .update();
    }

    @Override
    public Conversation lockConversation(UUID contactId, String whatsAppPhoneNumberId, UUID assigneeIfNew) {
        Objects.requireNonNull(contactId, "contactId");
        Objects.requireNonNull(whatsAppPhoneNumberId, "whatsAppPhoneNumberId");
        UUID tenant = systemTenant();
        jdbc.sql(INSERT_CONVERSATION)
                .param(tenant)
                .param(contactId)
                .param(whatsAppPhoneNumberId)
                .param(assigneeIfNew)
                .update();
        return jdbc.sql(LOCK_CONVERSATION)
                .param(tenant)
                .param(contactId)
                .query((row, index) -> new Conversation(
                        row.getObject("id", UUID.class), row.getObject("assigned_membership_id", UUID.class)))
                .single();
    }

    @Override
    public void recordInboundAt(UUID conversationId, Instant sentAt) {
        Objects.requireNonNull(conversationId, "conversationId");
        OffsetDateTime at = utc(sentAt);
        jdbc.sql(RECORD_INBOUND_AT)
                .param(at)
                .param(systemTenant())
                .param(conversationId)
                .param(at)
                .update();
    }

    @Override
    public UUID storeInbound(UUID conversationId, InboundMessage message) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(message, "message");
        Optional<UUID> id = jdbc.sql(INSERT_MESSAGE)
                .param(systemTenant())
                .param(conversationId)
                .param(message.providerMessageId())
                .param(message.type().code())
                .param(message.body())
                .param(utc(message.sentAt()))
                .query(UUID.class)
                .optional();
        // Callers check hasInboundMessage first, under the webhook row's lock; a conflict here is a bug.
        return id.orElseThrow(() -> new IllegalStateException("Inbound message already stored"));
    }

    /**
     * A profile name as kept: control characters and runs of white space become one space, trimmed, at most
     * {@link #MAX_NAME_LENGTH} code points; null when nothing is left. Never logged.
     */
    static String cleanName(String profileName) {
        if (profileName == null) return null;
        StringBuilder kept = new StringBuilder();
        int length = 0;
        boolean gap = false;
        for (int codePoint : profileName.codePoints().toArray()) {
            if (Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                gap = !kept.isEmpty();
                continue;
            }
            int needed = gap ? 2 : 1;
            if (length + needed > MAX_NAME_LENGTH) break;
            if (gap) kept.append(' ');
            kept.appendCodePoint(codePoint);
            length += needed;
            gap = false;
        }
        return kept.isEmpty() ? null : kept.toString();
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Inbound messages are processed by the system actor");
        return context.tenantId();
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC);
    }
}
