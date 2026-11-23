package com.northstar.integrations;

import com.northstar.contacts.OutboundContent;
import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.workflow.ActionView;
import java.util.Collection;
import java.util.UUID;

/**
 * Queues WhatsApp messages to a contact (step 2.6a, integration-specs.md section 2): the only way any module sends one.
 * Each call stores the message as queued and creates one {@value #SEND_TYPE} action in one transaction (the caller's,
 * if it has one): its recipient is the conversation's contact, so an opt-out refuses it (W32), and its payload is the
 * message ID only, never the text. The worker sends it later and checks the 24-hour window then, not now (W33).
 *
 * <p>Runs in the caller's tenant scope: {@link #queueText} and {@link #queueTemplate} as the system actor (the engine,
 * the AI), and refused with {@link com.northstar.contacts.AutomationPausedException} for a conversation a staff member
 * has taken over (step 2.9c); {@link #queueStaffReply} as an admin or staff member (step 2.9b). The idempotency key names the send:
 * queueing again with the same key stores no second message and creates no second action, and returns the first (P1,
 * P21). Another tenant's conversation or template is not found (P9).
 */
public interface WhatsAppMessages {

    /** The action type that sends a queued WhatsApp message. */
    String SEND_TYPE = "whatsapp.send";

    /** The queued message and its send action; created is false when the key had been queued before. */
    record Queued(UUID messageId, ActionView action, boolean created) {}

    /** The longest Idempotency-Key a staff reply takes. */
    int MAX_STAFF_KEY_LENGTH = 100;

    /**
     * A reply from the caller, an admin or a staff member (step 2.9b, build-document.md section 3): free text inside
     * the 24-hour window, an approved template with its values outside it, and to an opted-out contact only free text
     * inside the window the client opened by writing again after opting out (W32), audited as
     * {@code message.sent_after_opt_out}. The sender is the caller's own membership. A closed conversation is reopened.
     *
     * <p>The key is the request's Idempotency-Key, scoped to the caller's membership, so the same request sent twice
     * (a double click, two tabs, a retry) queues one message and one send (P21); the same key with another reply is
     * refused as {@code idempotency_key_reused}. The send checks the window again when it runs (W33).
     *
     * @param idempotencyKey 1 to {@value #MAX_STAFF_KEY_LENGTH} characters
     * @see com.northstar.contacts.OutboundMessages#queueStaffReply for the refusals
     */
    Queued queueStaffReply(UUID conversationId, OutboundContent content, String idempotencyKey);

    /**
     * Cancels the sends of these messages that have not run yet (pending, waiting for review, or paused), each with
     * this reason, and fails their messages with it through the send's closing hook (step 2.9c: taking a conversation
     * over cancels its queued automated sends). A send already being attempted is left to finish. Runs inside the
     * caller's transaction, as the system, an admin or a staff member, after the caller has locked the messages'
     * conversation; returns how many sends it cancelled.
     */
    int cancelQueuedSends(Collection<UUID> messageIds, String reason);

    /**
     * Free text, 1 to 4,096 characters. It is sent only if the window is open when the send runs; otherwise the send
     * fails as {@code window_closed_no_template} (it never turns into a template message).
     */
    Queued queueText(UUID conversationId, String text, OutboundMessages.Sender sender, String idempotencyKey);

    /**
     * One of the business's templates with its values, sent inside or outside the window, if the template is approved
     * when the send runs (W34). Otherwise its status decides (step 2.8c): paused blocks the send until the template is
     * approved again, and any other status fails it with its own code ({@code template_rejected},
     * {@code template_not_approved} and so on).
     */
    Queued queueTemplate(
            UUID conversationId,
            UUID templateId,
            TemplateParameters parameters,
            OutboundMessages.Sender sender,
            String idempotencyKey);
}
