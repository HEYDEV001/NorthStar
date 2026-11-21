package com.northstar.workflow;

import java.util.Objects;
import java.util.UUID;

/**
 * An action to create. needsReview creates it as needs_review: it runs only after an approver approves it
 * (workflow-spec.md section 9). The idempotency key is unique per tenant; creating the same key again returns the
 * existing action. payloadJson is the drafted content; leadId and stageEntryId may be null.
 *
 * <p>recipientContactId is the contact the action sends something to: required for a type whose handler's recipient is
 * {@link ActionRecipient#CONTACT}, and refused for any other (step 2.5). An action for a contact who has opted out is
 * created cancelled, with reason {@value OutboundCancellation#CONTACT_OPTED_OUT}, and never runs (W32).
 */
public record NewAction(
        String type,
        String idempotencyKey,
        ActionMode mode,
        boolean needsReview,
        String payloadJson,
        UUID leadId,
        UUID stageEntryId,
        UUID recipientContactId) {

    public NewAction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(mode, "mode");
        if (payloadJson == null) payloadJson = "{}";
    }
}
