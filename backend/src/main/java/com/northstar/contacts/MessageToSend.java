package com.northstar.contacts;

import java.util.UUID;

/**
 * An outbound message as the WhatsApp sender needs it when the send runs (step 2.6a), read in one statement: the
 * message, the business number its conversation is on, the contact's number, whether the 24-hour window is open (with
 * the sender's safety margin, on the database's clock) and, for a template message, the template as it is now.
 *
 * <p>Holds message text, template values and a phone number, so toString shows only IDs and states.
 *
 * @param status the message's status: queued until it is sent or fails
 * @param accepted whether Meta's message ID is already stored (a send that already happened, P1)
 * @param text the text of a text message; null for a template message
 * @param template the template of a template message; null for a text message
 */
public record MessageToSend(
        UUID messageId,
        String type,
        String status,
        boolean accepted,
        String whatsAppPhoneNumberId,
        String recipientPhone,
        boolean windowOpen,
        String text,
        Template template) {

    /** A template as it is when the send runs: its status may have changed since the message was queued (W34). */
    public record Template(UUID id, String name, String language, String status, TemplateParameters parameters) {
        @Override
        public String toString() {
            return "MessageToSend.Template[id=" + id + ", status=" + status + "]";
        }
    }

    public boolean isQueued() {
        return "queued".equals(status);
    }

    @Override
    public String toString() {
        return "MessageToSend[messageId=" + messageId + ", type=" + type + ", status=" + status + ", accepted="
                + accepted + ", windowOpen=" + windowOpen + "]";
    }
}
