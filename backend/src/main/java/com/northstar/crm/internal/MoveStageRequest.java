package com.northstar.crm.internal;

import com.northstar.api.BadRequestException;
import com.northstar.workflow.MoveReason;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A member's stage move (step 3.3b, workflow-spec.md section 3).
 *
 * <pre>
 * {"to": "needs_human", "expectedVersion": 3, "reason": "client_request", "force": false}
 * </pre>
 *
 * {@code to} is a stage key of the lead's version; {@code expectedVersion} the lead's version the move was decided on
 * (P7); {@code reason} one of the fixed reason codes ({@link MoveReason}; anything else is 400 {@value #REASON_INVALID});
 * {@code force}, an admin's forced move, defaults to false. No field holds free text.
 */
record MoveStageRequest(
        @NotNull @Pattern(regexp = "[a-z][a-z0-9_]{0,49}") String to,

        @NotNull @Min(0) Integer expectedVersion,

        @NotNull @Size(max = 64) String reason,
        Boolean force) {

    static final String REASON_INVALID = "reason_invalid";

    /** The reason, or 400 {@value #REASON_INVALID}. */
    MoveReason moveReason() {
        return MoveReason.fromCode(reason)
                .orElseThrow(() -> new BadRequestException(
                        REASON_INVALID, "The reason must be one of the fixed reason codes for a stage move"));
    }

    boolean forced() {
        return Boolean.TRUE.equals(force);
    }
}
