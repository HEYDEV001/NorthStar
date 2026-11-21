package com.northstar.workflow;

import java.util.Objects;
import java.util.UUID;

/**
 * An action that has just closed without succeeding (step 2.6a), as {@link ActionHandler#closed} sees it: it will never
 * run again. code is the fixed code it closed with: the cancel reason for a cancelled action (e.g.
 * {@value OutboundCancellation#CONTACT_OPTED_OUT}), the last error code for a failed one (e.g. "timeout").
 */
public record ClosedAction(UUID actionId, String type, String payloadJson, Closing closing, String code) {

    /** How the action closed. Phase 3 adds a skipped action (Skip on the Failed Actions screen). */
    public enum Closing {
        /** Failed for good: a permanent error, its attempts used up, or its lease lost on its last attempt. */
        FAILED,
        /** It no longer applies (step 2.5: its recipient opted out). */
        CANCELLED
    }

    public ClosedAction {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        Objects.requireNonNull(closing, "closing");
        if (code == null || !code.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("A closed action's code is a fixed code");
        }
    }
}
