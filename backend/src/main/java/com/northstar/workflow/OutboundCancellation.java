package com.northstar.workflow;

import java.util.UUID;

/**
 * Cancels what would still message a contact who has opted out (step 2.5, W32; workflow-spec.md section 13 Opt-out).
 * Runs only inside the caller's transaction (none is opened here) and only as the system actor, in the current tenant,
 * so the cancellation commits with the opt-out or not at all.
 */
public interface OutboundCancellation {

    /** The fixed code an action cancelled because its recipient opted out carries as its cancel_reason. */
    String CONTACT_OPTED_OUT = "contact_opted_out";

    /**
     * Cancels every action whose recipient is this contact and that could still run: pending (due now, scheduled for
     * later, or waiting for a retry), needs_review and paused, each with {@value #CONTACT_OPTED_OUT}. An attempt already
     * processing is not cancelled, as it cannot be recalled; its next claim is refused instead. Their queued jobs are
     * left to find the action cancelled and do nothing. Returns how many were cancelled; running it again cancels
     * nothing more.
     */
    int cancelForOptedOutContact(UUID contactId);
}
