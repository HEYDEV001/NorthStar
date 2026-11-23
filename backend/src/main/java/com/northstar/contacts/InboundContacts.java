package com.northstar.contacts;

import java.time.Instant;
import java.util.UUID;

/**
 * What the inbound WhatsApp job writes about a contact (step 2.4b, data-model.md section 4): the contact, its source,
 * its conversation and the message. Every method runs only inside the caller's transaction (none is opened here, so
 * the job's writes commit or roll back together, P3) and only as the system actor, in the current tenant.
 *
 * <p>Message text, profile names and phone numbers are stored, never logged.
 */
public interface InboundContacts {

    /** Whether an inbound message with this provider ID (the wamid) is already stored in the tenant (P1). */
    boolean hasInboundMessage(String providerMessageId);

    /**
     * The contact with this E.164 number, created if it is new, and locked ({@code select ... for update}) until the
     * transaction ends, so one contact's messages are processed one at a time and the lead decision cannot race (P3).
     * A profile name fills an empty name only, cleaned of control characters and capped at
     * {@value #MAX_NAME_LENGTH} characters, and never replaces a name already there; null leaves the name alone.
     */
    UUID lockContact(String normalizedPhone, String profileName);

    /** Records that the contact arrived from this source, once per distinct source (insert-only). */
    void recordSource(UUID contactId, ContactSource source, Instant firstSeenAt);

    /**
     * The contact's WhatsApp conversation, created on this business number with {@code assigneeIfNew} (null:
     * unassigned) if it has none, and locked until the transaction ends. An existing conversation keeps its number and
     * its assignee.
     */
    Conversation lockConversation(UUID contactId, String whatsAppPhoneNumberId, UUID assigneeIfNew);

    /**
     * Moves the conversation's {@code last_inbound_at} forward to {@code sentAt}, WhatsApp's timestamp of an inbound
     * message, and never back, so a late or redelivered webhook never stretches the 24-hour window past Meta's.
     */
    void recordInboundAt(UUID conversationId, Instant sentAt);

    /**
     * Moves the conversation's {@code last_contact_message_at} forward to {@code sentAt}, never back (step 2.9b): the
     * WhatsApp timestamp of the client's latest message that counts as the client writing, which the caller decides
     * (every inbound message but a reaction or a system message). The staff reply exception to an opt-out opens from it
     * (W32).
     */
    void recordContactMessageAt(UUID conversationId, Instant sentAt);

    /**
     * Stores the inbound message in the conversation, which must already be locked ({@link #lockConversation}), and
     * records it as the conversation's latest activity: {@code last_message_at} moves forward, and a {@code closed}
     * conversation is open again (step 2.9a: a new inbound message of any type reopens it, an opt-out included). Fails if
     * a message with the same provider ID is already stored; callers check {@link #hasInboundMessage} first, so a
     * duplicate changes nothing.
     */
    StoredInbound storeInbound(UUID conversationId, InboundMessage message);

    /** The stored inbound message, and whether storing it reopened its closed conversation. */
    record StoredInbound(UUID messageId, boolean reopened) {}

    /** The longest profile name kept, in characters (code points). */
    int MAX_NAME_LENGTH = 200;

    /** A conversation and its assignee (null: unassigned). */
    record Conversation(UUID id, UUID assignedMembershipId) {}
}
