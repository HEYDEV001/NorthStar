package com.northstar.workflow;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The engine's input log ({@code events}, data-model.md section 6, workflow-spec.md section 6). Each event is recorded
 * once per dedupe key, so recording it again has no effect (P1); the engine (Phase 3) reads them and sets
 * processed_at. Every method runs only inside the caller's transaction, in the current tenant, and as the system actor,
 * except {@link #recordStaffAction}, which only an admin or a staff member records (step 2.9c).
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

    /**
     * Records {@code optout.received} for an inbound message that opted its sender out (step 2.5, W32), under the dedupe
     * key {@code optout.received:<wamid>}. Such a message records this event instead of {@code message.received}, so
     * the engine and the AI never treat it as conversation. Returns false if it was already recorded.
     */
    boolean recordOptOutReceived(OptOutReceived event);

    /**
     * Records {@code message.status} for a delivery status Meta reported for an outbound message (step 2.7), under the
     * dedupe key {@code message.status:<wamid>:<status>}: every status that matched a message and was not ignored
     * (sent, delivered, read, failed; a late delivered after read included). Returns false if it was already recorded.
     */
    boolean recordMessageStatus(MessageStatus event);

    /**
     * Records {@code staff.action} for a member's take over or hand back of a conversation (step 2.9c,
     * workflow-spec.md section 6), on the open lead of the conversation's contact, when there is one the caller can
     * see: the engine acts on leads only (Phase 3 treats a take over as a global trigger and cancels the lead's timers).
     * Without such a lead nothing is recorded, and the caller's audit entry is the record. The dedupe key is
     * {@code staff.action:<action>:<conversation>:<take over time in microseconds>}, unique for each take over and the
     * hand back that ends it. Returns false if nothing was recorded (no lead, or recorded before).
     */
    boolean recordStaffAction(StaffAction event);

    /** What a member did to a conversation's automation (step 2.9c). */
    enum StaffActionKind {
        TAKE_OVER("take_over", "conversation_taken_over"),
        HAND_BACK("hand_back", "conversation_handed_back");

        private final String code;
        private final String reason;

        StaffActionKind(String code, String reason) {
            this.code = code;
            this.reason = reason;
        }

        public String code() {
            return code;
        }

        /** The fixed reason recorded with it: there is no other (decided 22 Nov 2026, the 2.9 plan). */
        public String reason() {
            return reason;
        }
    }

    /**
     * A take over or hand back (step 2.9c): the conversation, its contact, the member who did it, how many automated
     * sends it cancelled (always 0 for a hand back), when it happened, and when the take over it began or ended began,
     * which names it in the dedupe key.
     */
    record StaffAction(
            StaffActionKind kind,
            UUID conversationId,
            UUID contactId,
            UUID membershipId,
            int cancelledSends,
            Instant at,
            Instant takeOverAt) {

        public StaffAction {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(membershipId, "membershipId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(takeOverAt, "takeOverAt");
            if (cancelledSends < 0 || (kind == StaffActionKind.HAND_BACK && cancelledSends != 0)) {
                throw new IllegalArgumentException("Only a take over cancels sends");
            }
        }
    }

    /**
     * A delivery status of an outbound message (step 2.7). status is sent, delivered, read or failed; errorCode is the
     * fixed code of a failed one, and only of one. statusAt is Meta's timestamp. There is no lead yet: sends name their
     * lead from Phase 3.
     */
    record MessageStatus(
            String providerMessageId,
            UUID webhookEventId,
            UUID contactId,
            UUID conversationId,
            UUID messageId,
            String status,
            String errorCode,
            Instant statusAt) {

        private static final Pattern STATUS = Pattern.compile("sent|delivered|read|failed");
        private static final Pattern ERROR_CODE = Pattern.compile("[a-z0-9_]{1,64}");

        public MessageStatus {
            Objects.requireNonNull(providerMessageId, "providerMessageId");
            Objects.requireNonNull(webhookEventId, "webhookEventId");
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(statusAt, "statusAt");
            if (status == null || !STATUS.matcher(status).matches()) {
                throw new IllegalArgumentException("status must be sent, delivered, read or failed");
            }
            if (("failed".equals(status)) != (errorCode != null)
                    || (errorCode != null && !ERROR_CODE.matcher(errorCode).matches())) {
                throw new IllegalArgumentException("A failed status, and only one, carries a fixed error code");
            }
        }

        /** The provider ID is in the dedupe key only; it is left out here so it never reaches a log. */
        @Override
        public String toString() {
            return "MessageStatus[messageId=" + messageId + ", status=" + status + "]";
        }
    }

    /**
     * An inbound opt-out message stored by step 2.5. It never opens a lead: lead is {@code joined} (leadId the open
     * lead) or {@code none} (leadId null). The keyword it matched is not recorded.
     */
    record OptOutReceived(
            String providerMessageId,
            UUID leadId,
            LeadOutcome lead,
            UUID webhookEventId,
            UUID contactId,
            UUID conversationId,
            UUID messageId,
            Instant sentAt) {

        public OptOutReceived {
            Objects.requireNonNull(providerMessageId, "providerMessageId");
            Objects.requireNonNull(lead, "lead");
            Objects.requireNonNull(webhookEventId, "webhookEventId");
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(sentAt, "sentAt");
            if (lead == LeadOutcome.OPENED) throw new IllegalArgumentException("An opt-out message never opens a lead");
            if ((leadId == null) != (lead == LeadOutcome.NONE)) {
                throw new IllegalArgumentException("A message has a lead exactly when it joined one");
            }
        }

        /** The provider ID is in the dedupe key only; it is left out here so it never reaches a log. */
        @Override
        public String toString() {
            return "OptOutReceived[messageId=" + messageId + ", lead=" + lead.code() + "]";
        }
    }

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
