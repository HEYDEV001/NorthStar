package com.northstar.integrations.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Processes one stored account-level change (step 2.8a; integration-specs.md section 2, Quality and limits), handed
 * over by {@link WhatsAppInboundProcessor} with its row already locked, in the caller's transaction as the system actor:
 *
 * <ul>
 *   <li>a messaging limit Meta documents is recorded on every number of that WABA in this tenant (limits are set per
 *       business portfolio and shared by its numbers), in arrival order: the limit drives nothing yet, and every sync
 *       reads it again;
 *   <li>a change without the limit field changes nothing and is closed;
 *   <li>a limit Northstar does not recognise is not stored, and the row is closed as {@value #LIMIT_UNRECOGNISED};
 *   <li>a WABA with no number in this tenant (a slice that names another tenant's account, which routing never
 *       produces) changes nothing and is closed as {@value #ACCOUNT_NOT_FOUND} (P9);
 *   <li>an unusable slice is closed as {@code payload_invalid}.
 * </ul>
 *
 * <p>Locks: the webhook row, then the WABA's number rows in ID order; never a contact, conversation, message or action.
 * Nothing here logs; the job logs the outcome after the commit, with IDs, counts and fixed codes only.
 */
@Component
class WhatsAppAccountUpdateProcessor {

    static final String LIMIT_UNRECOGNISED = "messaging_limit_unrecognised";
    static final String ACCOUNT_NOT_FOUND = "account_not_found";

    private final WebhookEventProcessing rows;
    private final WhatsAppAccountHealth accounts;

    WhatsAppAccountUpdateProcessor(WebhookEventProcessing rows, WhatsAppAccountHealth accounts) {
        this.rows = rows;
        this.accounts = accounts;
    }

    sealed interface Outcome {}

    /** The limit recorded: how many of the WABA's numbers there are, and how many it changed. */
    record LimitApplied(MessagingLimit limit, int numbers, int changed) implements Outcome {}

    /** The change carried no limit; nothing changed. */
    record LimitMissing() implements Outcome {}

    /** Closed with this fixed code; nothing changed. */
    record Rejected(String errorCode) implements Outcome {}

    /** Runs inside the caller's transaction, with the row locked and not yet processed. */
    Outcome process(UUID webhookEventId, WebhookEventProcessing.Locked row) {
        WhatsAppAccountUpdate.Usable change;
        switch (WhatsAppAccountUpdate.parse(row.providerEventId(), row.payloadJson())) {
            case WhatsAppAccountUpdate.Rejected rejected -> {
                return close(webhookEventId, rejected.errorCode());
            }
            case WhatsAppAccountUpdate.Usable usable -> change = usable;
        }
        if (!change.limitPresent()) {
            rows.finish(webhookEventId, null);
            return new LimitMissing();
        }
        if (change.limit().isEmpty()) return close(webhookEventId, LIMIT_UNRECOGNISED);

        WhatsAppAccountHealth.LimitRecorded recorded =
                accounts.recordLimit(change.wabaId(), change.limit().get());
        if (recorded.numbers() == 0) return close(webhookEventId, ACCOUNT_NOT_FOUND);
        rows.finish(webhookEventId, null);
        return new LimitApplied(change.limit().get(), recorded.numbers(), recorded.changed());
    }

    private Rejected close(UUID webhookEventId, String code) {
        rows.finish(webhookEventId, code);
        return new Rejected(code);
    }
}
