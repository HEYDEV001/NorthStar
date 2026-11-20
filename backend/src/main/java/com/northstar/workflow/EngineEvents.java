package com.northstar.workflow;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The engine's input log ({@code events}, data-model.md section 6, workflow-spec.md section 6). Each event is recorded
 * once per dedupe key, so recording it again has no effect (P1); the engine (Phase 3) reads them and sets
 * processed_at. Every method runs only inside the caller's transaction and only as the system actor, in the current
 * tenant.
 *
 * <p>Payloads hold IDs, fixed codes and timestamps only, never message text, names or phone numbers: each event type
 * is a record of exactly those, so free text has nowhere to go.
 */
public interface EngineEvents {

    /**
     * Records {@code message.received} for an inbound message, under the dedupe key
     * {@code message.received:<wamid>}. Returns false if it was already recorded.
     */
    boolean recordMessageReceived(MessageReceived event);

    /** An inbound message stored by step 2.4b. leadId is null when the message joined or opened no lead. */
    record MessageReceived(
            String providerMessageId,
            UUID leadId,
            LeadOutcome lead,
            UUID webhookEventId,
            UUID contactId,
            UUID conversationId,
            UUID messageId,
            String messageType,
            Instant sentAt) {

        private static final Pattern CODE = Pattern.compile("[a-z][a-z_]{0,31}");

        public MessageReceived {
            Objects.requireNonNull(providerMessageId, "providerMessageId");
            Objects.requireNonNull(lead, "lead");
            Objects.requireNonNull(webhookEventId, "webhookEventId");
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(sentAt, "sentAt");
            if (messageType == null || !CODE.matcher(messageType).matches()) {
                throw new IllegalArgumentException("messageType must be a fixed code");
            }
            if ((leadId == null) != (lead == LeadOutcome.NONE)) {
                throw new IllegalArgumentException("A message has a lead exactly when it opened or joined one");
            }
        }

        /** The provider ID is in the dedupe key only; it is left out here so it never reaches a log. */
        @Override
        public String toString() {
            return "MessageReceived[messageId=" + messageId + ", lead=" + lead.code() + "]";
        }
    }

    /** What an inbound message did about a lead: opened a new one, joined the open one, or neither. */
    enum LeadOutcome {
        OPENED,
        JOINED,
        NONE;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
