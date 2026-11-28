package com.northstar.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A member's stage moves (step 3.3, workflow-spec.md section 3): an admin, or the staff member the lead is assigned to,
 * moves a lead along a move its own workflow version allows; an admin alone may force any other move to a stage of that
 * version. Every method runs only inside the caller's transaction, as an admin or a staff member, in the current
 * tenant, with RLS: a staff member sees and moves only leads assigned to their own active membership. {@link EngineMoves}
 * is the system's path.
 *
 * <p>A move names the lead's version it was made against (P7), and always a reason (a fixed code). It changes the stage
 * in one update that checks and increments the version, writes a {@code stage_changed} timeline row and records a
 * {@code staff.action} event ({@code move_stage}); a forced move also writes the audit entry
 * {@value #STAGE_FORCED}.
 *
 * <p>Refusals (each an API exception, so the caller's transaction rolls back): 403 {@code admin_only} (a staff member
 * forcing a move), {@code no_active_membership}; 404 for a lead the caller cannot see, a deleted one or one of another
 * business (P9, P11); 409 {@link StaleLeadException stale_lead}, {@code workflow_version_unsupported},
 * {@code stage_unchanged}, {@code transition_not_allowed}, {@code transition_engine_only} (an exit only a verified
 * payment or refund event moves) and {@code contact_has_open_lead} (re-opening a closed lead whose contact has another
 * open lead); 422 {@code stage_unknown} (a stage the lead's version does not declare).
 */
public interface LeadMoves {

    /** The audit entry of a forced move, with the stages as from and to. */
    String STAGE_FORCED = "lead.stage_forced";

    /**
     * Moves the lead to {@code toStage}. With {@code force} (admins only) a move outside the allowed ones is made too, and
     * audited; a move that is allowed anyway is made as that move, not as a forced one.
     */
    Moved move(UUID leadId, String toStage, int expectedVersion, MoveReason reason, boolean force);

    /** The lead's stage as the caller sees it: empty for a lead they cannot see, a deleted one or none. */
    Optional<LeadStage> stage(UUID leadId);

    /** A lead's stage, the stage Needs Human was entered from, when it closed, and its version (P7). */
    record LeadStage(
            UUID id,
            UUID workflowVersionId,
            String stage,
            String previousStage,
            Instant stageEnteredAt,
            Instant closedAt,
            int version) {

        public LeadStage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(workflowVersionId, "workflowVersionId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(stageEnteredAt, "stageEnteredAt");
        }
    }

    /**
     * A move made: the lead as it now is, its timeline row, and the kind of move ({@code exit}, {@code needs_human},
     * {@code hand_back}, {@code lost}, {@code restore} or {@code forced}).
     */
    record Moved(LeadStage lead, UUID timelineEventId, String kind) {

        public Moved {
            Objects.requireNonNull(lead, "lead");
            Objects.requireNonNull(timelineEventId, "timelineEventId");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
