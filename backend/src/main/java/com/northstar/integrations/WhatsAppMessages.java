package com.northstar.integrations;

import com.northstar.contacts.OutboundMessages;
import com.northstar.contacts.TemplateParameters;
import com.northstar.workflow.ActionView;
import java.util.UUID;

/**
 * Queues WhatsApp messages to a contact (step 2.6a, integration-specs.md section 2): the only way any module sends one.
 * Each call stores the message as queued and creates one {@value #SEND_TYPE} action in one transaction (the caller's,
 * if it has one): its recipient is the conversation's contact, so an opt-out refuses it (W32), and its payload is the
 * message ID only, never the text. The worker sends it later and checks the 24-hour window then, not now (W33).
 *
 * <p>Runs in the caller's tenant scope, as the system actor only until step 2.9 adds staff replies. The idempotency key
 * names the send: queueing again with the same key stores no second message and creates no second action, and returns
 * the first (P1). Another tenant's conversation or template is not found (P9).
 */
public interface WhatsAppMessages {

    /** The action type that sends a queued WhatsApp message. */
    String SEND_TYPE = "whatsapp.send";

    /** The queued message and its send action; created is false when the key had been queued before. */
    record Queued(UUID messageId, ActionView action, boolean created) {}

    /**
     * Free text, 1 to 4,096 characters. It is sent only if the window is open when the send runs; otherwise the send
     * fails as {@code window_closed_no_template} (it never turns into a template message).
     */
    Queued queueText(UUID conversationId, String text, OutboundMessages.Sender sender, String idempotencyKey);

    /**
     * One of the business's templates with its values, sent inside or outside the window, if the template is approved
     * when the send runs (W34; otherwise the send fails as {@code template_not_approved}).
     */
    Queued queueTemplate(
            UUID conversationId,
            UUID templateId,
            TemplateParameters parameters,
            OutboundMessages.Sender sender,
            String idempotencyKey);
}
