package com.northstar.contacts;

import java.time.Duration;
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
     * Records that Meta accepted the message: its provider ID (set once, messages_set_once), status sent and sent_at
     * now. Only a message still queued without a provider ID changes; returns whether it did.
     */
    boolean markSent(UUID messageId, String providerMessageId);

    /**
     * Records that the message will not be sent: status failed with a fixed code. Only a message still queued without
     * a provider ID changes, so a message Meta has accepted is never marked failed; returns whether it did.
     */
    boolean markFailed(UUID messageId, String errorCode);
}
