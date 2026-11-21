package com.northstar.integrations.internal;

import com.northstar.contacts.TemplateParameters;
import com.northstar.workflow.ActionOutcome;
import java.util.Objects;
import java.util.UUID;

/**
 * Sends one message through WhatsApp's Cloud API on a business's number (integration-specs.md section 2): the provider
 * adapter behind the {@code whatsapp.send} handler, and nothing else calls it. Step 2.6a declares it and tests use a
 * fake; the Cloud API adapter, {@link CloudApiWhatsAppSender}, which maps Meta's error codes, is step 2.6b's.
 *
 * <p>Meta takes no idempotency key, so the adapter sends exactly once per call and the handler decides whether to call
 * at all. Its answer is already classified retryable, permanent or auth (integration-specs.md section 1). The token is
 * read only to build the request; nothing logs it, the message, the number or Meta's message ID.
 */
interface WhatsAppSender {

    WhatsAppSendResult send(OutgoingWhatsApp message, Secret businessToken);

    /** One message to one contact, from one of the business's numbers. toString shows only the message ID. */
    record OutgoingWhatsApp(UUID messageId, String phoneNumberId, String to, Body body) {
        public OutgoingWhatsApp {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(phoneNumberId, "phoneNumberId");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(body, "body");
        }

        @Override
        public String toString() {
            return "OutgoingWhatsApp[messageId=" + messageId + ", " + body.kind() + "]";
        }
    }

    /** What the message says. */
    sealed interface Body {
        /** "text" or "template", for logs. */
        String kind();
    }

    record Text(String text) implements Body {
        @Override
        public String kind() {
            return "text";
        }

        @Override
        public String toString() {
            return "Text[redacted]";
        }
    }

    record Template(String name, String language, TemplateParameters parameters) implements Body {
        @Override
        public String kind() {
            return "template";
        }

        @Override
        public String toString() {
            return "Template[redacted]";
        }
    }

    /** Meta's answer: accepted with its message ID (the wamid), or a classified failure. */
    sealed interface WhatsAppSendResult {}

    /** Accepted for delivery; delivery itself is reported by status webhooks (step 2.7). */
    record Accepted(String providerMessageId) implements WhatsAppSendResult {
        public Accepted {
            if (providerMessageId == null || providerMessageId.isBlank() || providerMessageId.length() > 200) {
                throw new IllegalArgumentException("A provider message ID is 1 to 200 characters");
            }
        }

        @Override
        public String toString() {
            return "Accepted[redacted]";
        }
    }

    record Rejected(ActionOutcome.Failed failure) implements WhatsAppSendResult {
        public Rejected {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
