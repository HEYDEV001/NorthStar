package com.northstar.contacts;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound WhatsApp messages (step 2.6a, data-model.md section 4): queued in a conversation, read when the send runs,
 * and marked sent or failed. Every method runs only inside the caller's transaction (none is opened here) and only as
 * the system actor, in the current tenant; a record the caller cannot see is not found (P9).
 *
 * <p>Message text, template values and phone numbers are stored, never logged, and never put in an exception message.
 */
public interface OutboundMessages {

    /** Who an outbound message is from. A staff member's reply comes with step 2.9. */
    enum Sender {
        SYSTEM("system"),
        AI("ai");

        private final String code;

        Sender(String code) {
            this.code = code;
        }

        /** messages.sender_type */
        public String code() {
            return code;
        }
    }

    /**
     * The stored message and its recipient: the contact of the conversation it is in. stored is false when a message
     * with this ID was already there, in which case nothing was written and the recipient is that message's.
     */
    record Queued(UUID messageId, UUID recipientContactId, boolean stored) {}

    /**
     * Stores the message as queued under this ID in the conversation, unless a message with the ID is already stored,
     * so queueing the same send again stores nothing more (P1).
     *
     * @throws com.northstar.api.NotFoundException if the conversation, or a template message's template, is not
     *     visible in the current tenant
     */
    Queued queue(UUID messageId, UUID conversationId, Sender sender, OutboundContent content);

    /**
     * The outbound message with this ID as the sender needs it, or empty if the current tenant has none. The window is
     * open only while {@code conversations.window_expires_at} is more than {@code windowMargin} away on the database's
     * clock; a conversation with no inbound message has no window.
     */
    Optional<MessageToSend> toSend(UUID messageId, Duration windowMargin);

    /**
     * The template's outbound messages still queued (step 2.8c, W34): the sends its status change applies to, in ID
     * order. Their contacts are share-locked first, in contact ID order, before anything else is read, so this takes
     * its locks in the opt-out's order (the contact, then its actions, then their messages) and waits for an opt-out in
     * progress, then sees its result: a message the opt-out failed is no longer queued. Only messages of the locked
     * contacts are returned, so one queued meanwhile for another contact is left to its send, which reads the template
     * when it runs. Call it with the template's row already locked, so two changes of one template run one after the
     * other. Locks nothing on the messages themselves.
     */
    List<UUID> lockQueuedForTemplate(UUID templateId);

    /**
     * Records that Meta accepted the message: its provider ID (set once, messages_set_once), status sent and sent_at
     * now. Only a message still queued without a provider ID changes; returns whether it did.
     */
    boolean markSent(UUID messageId, String providerMessageId);

    /**
     * Records that the message will not be sent: status failed with a fixed code. Only a message still queued without
     * a provider ID changes, so a message Meta has accepted is never marked failed; returns whether it did.
     */
    boolean markFailed(UUID messageId, String errorCode);

    /**
     * What a delivery status did to the outbound message Meta knows by its provider ID (step 2.7). Every result but
     * {@link NotFound} names the message, its conversation and its contact, for the engine event.
     */
    sealed interface StatusUpdate {}

    /** No outbound message of the tenant has this provider ID, or not yet (Meta's webhook beat the send's commit). */
    record NotFound() implements StatusUpdate {}

    /** The status moved forward, or the message failed: from and to are messages.status values. */
    record Applied(UUID messageId, UUID conversationId, UUID contactId, String from, String to)
            implements StatusUpdate {}

    /** The message already had this status (Meta's sent after the send's own, or a replay): nothing changed. */
    record Unchanged(UUID messageId, UUID conversationId, UUID contactId, String status) implements StatusUpdate {}

    /**
     * A delivered status for a message already read (Meta may send read first, or without delivered): the status stays
     * read, and delivered_at was filled if it was empty (filled says whether it was).
     */
    record LateDelivered(UUID messageId, UUID conversationId, UUID contactId, boolean filled) implements StatusUpdate {}

    /**
     * The status would move the message backward (sent after delivered), or is a failure after delivered or read, or
     * anything after failed: nothing changed.
     */
    record Ignored(UUID messageId, String from, String to) implements StatusUpdate {}

    /**
     * Applies a delivery status to the outbound message with this provider ID, which it locks until the transaction
     * ends (and nothing else: not its conversation or contact). Status only moves forward, queued &lt; sent &lt;
     * delivered &lt; read; failed replaces queued or sent only, and nothing leaves failed (the database's
     * messages_status_forward refuses anything else). {@code at} is Meta's timestamp: it fills delivered_at or read_at,
     * each set once. {@code errorCode} is the fixed code of a failed status, and only of one.
     */
    StatusUpdate recordStatus(String providerMessageId, DeliveryStatus status, Instant at, String errorCode);
}
