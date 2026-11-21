package com.northstar.integrations.internal;

import com.northstar.contacts.DeliveryStatus;
import com.northstar.contacts.OutboundMessages;
import com.northstar.workflow.EngineEvents;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Applies one stored delivery status to its outbound message (step 2.7; integration-specs.md section 2, How step 2.7
 * applies statuses), inside the inbound job's one transaction, which has already locked the webhook row and found it
 * unprocessed. Then, in order: the slice; the message, locked alone (never its conversation, contact or action, so
 * this path takes the webhook row and then the message, and nothing else); the status, forward only; the engine event
 * {@code message.status}; and processed_at.
 *
 * <ul>
 *   <li>Forward, or a failure of a sent message: applied and recorded. A failure's code comes from Meta's code through
 *       {@link MetaErrors#codeFor}; the send action stays succeeded.
 *   <li>The same status again: nothing changes; recorded under its dedupe key (nothing new if it is there).
 *   <li>A late delivered after read: delivered_at filled if empty, the status left read, delivered recorded.
 *   <li>Backward, or a failure after delivered or read: ignored, nothing recorded.
 *   <li>No message with that wamid yet: left unprocessed with {@value #MESSAGE_NOT_FOUND_YET}, which the sweep retries
 *       and closes at 24 hours.
 *   <li>A status Northstar does not track ({@code played}): closed as {@value #STATUS_UNSUPPORTED}.
 * </ul>
 *
 * <p>Nothing here logs; the job logs the outcome after the commit, with IDs, statuses and codes only.
 */
@Component
class WhatsAppStatusProcessor {

    /** Retryable: no outbound message has the status's wamid yet. Kept as the code if a 24-hour close comes first. */
    static final String MESSAGE_NOT_FOUND_YET = "message_not_found_yet";

    /** A status Northstar does not track, such as {@code played} (voice messages, which it does not send). */
    static final String STATUS_UNSUPPORTED = "status_unsupported";

    /** What one status row did. */
    sealed interface Outcome {}

    /** The status moved the message forward, or failed it (errorCode set exactly then). */
    record Applied(UUID messageId, String from, String to, String errorCode) implements Outcome {}

    /** The message already had this status. */
    record Unchanged(UUID messageId, String status) implements Outcome {}

    /** A delivered status for a read message: delivered_at filled if it was empty. */
    record LateDelivered(UUID messageId, boolean filled) implements Outcome {}

    /** Backward, a late failure, or anything after failed: ignored. */
    record Ignored(UUID messageId, String from, String to) implements Outcome {}

    /** No message with the wamid yet: left for the sweep. */
    record Waiting() implements Outcome {}

    /** A status Northstar does not track: closed. */
    record Unsupported() implements Outcome {}

    /** The slice was unusable: closed with this code. */
    record Rejected(String errorCode) implements Outcome {}

    private final WebhookEventProcessing rows;
    private final OutboundMessages messages;
    private final EngineEvents events;

    WhatsAppStatusProcessor(WebhookEventProcessing rows, OutboundMessages messages, EngineEvents events) {
        this.rows = rows;
        this.messages = messages;
        this.events = events;
    }

    /** Runs inside the caller's transaction, on a webhook row it has locked and found unprocessed. */
    Outcome process(UUID webhookEventId, WebhookEventProcessing.Locked row) {
        WhatsAppStatusUpdate update;
        switch (WhatsAppStatusUpdate.parse(row.providerEventId(), row.payloadJson())) {
            case WhatsAppStatusUpdate.Rejected rejected -> {
                rows.finish(webhookEventId, rejected.errorCode());
                return new Rejected(rejected.errorCode());
            }
            case WhatsAppStatusUpdate.Usable usable -> update = usable.status();
        }
        Optional<DeliveryStatus> tracked = DeliveryStatus.fromCode(update.status());
        if (tracked.isEmpty()) {
            rows.finish(webhookEventId, STATUS_UNSUPPORTED);
            return new Unsupported();
        }
        DeliveryStatus status = tracked.get();
        String errorCode = status == DeliveryStatus.FAILED ? MetaErrors.codeFor(update.metaErrorCode()) : null;

        Outcome outcome = switch (messages.recordStatus(update.wamid(), status, update.at(), errorCode)) {
            case OutboundMessages.NotFound notFound -> {
                rows.recordRetryableFailure(webhookEventId, MESSAGE_NOT_FOUND_YET);
                yield new Waiting();
            }
            case OutboundMessages.Applied applied -> {
                record(
                        webhookEventId,
                        update,
                        applied.contactId(),
                        applied.conversationId(),
                        applied.messageId(),
                        errorCode);
                yield new Applied(applied.messageId(), applied.from(), applied.to(), errorCode);
            }
            case OutboundMessages.Unchanged unchanged -> {
                record(
                        webhookEventId,
                        update,
                        unchanged.contactId(),
                        unchanged.conversationId(),
                        unchanged.messageId(),
                        errorCode);
                yield new Unchanged(unchanged.messageId(), unchanged.status());
            }
            case OutboundMessages.LateDelivered late -> {
                record(webhookEventId, update, late.contactId(), late.conversationId(), late.messageId(), null);
                yield new LateDelivered(late.messageId(), late.filled());
            }
            case OutboundMessages.Ignored ignored -> new Ignored(ignored.messageId(), ignored.from(), ignored.to());
        };
        if (!(outcome instanceof Waiting)) rows.finish(webhookEventId, null);
        return outcome;
    }

    private void record(
            UUID webhookEventId,
            WhatsAppStatusUpdate update,
            UUID contactId,
            UUID conversationId,
            UUID messageId,
            String errorCode) {
        events.recordMessageStatus(new EngineEvents.MessageStatus(
                update.wamid(),
                webhookEventId,
                contactId,
                conversationId,
                messageId,
                update.status(),
                errorCode,
                update.at()));
    }
}
