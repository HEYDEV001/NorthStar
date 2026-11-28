package com.northstar.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Leads a member enters by hand (step 2.10, build-document.md section 3; workflow-spec.md section 3: every lead starts
 * in {@value LeadIntake#FIRST_STAGE}). Every method runs only inside the caller's transaction, as an admin or a staff
 * member, in the current tenant, with RLS: a staff member sees and opens only leads assigned to their own active
 * membership ({@code leads_staff_assigned}). {@link LeadIntake} is the system's path for inbound messages.
 */
public interface LeadEntry {

    /**
     * The tenant's published workflow version for new leads, as {@link LeadIntake#publishedWorkflowVersion}: empty when
     * no workflow is published, or when more than one is.
     */
    Optional<UUID> publishedWorkflowVersion();

    /**
     * Opens a {@code manual} lead with this ID for the contact, in stage {@value LeadIntake#FIRST_STAGE}, assigned to
     * the membership and linked to no conversation (the inbound job links it at the client's next message, step 2.4b).
     * Returns false, opening nothing, when the contact already has an open lead (one open lead per contact,
     * data-model.md section 13).
     */
    boolean open(UUID leadId, UUID contactId, UUID workflowVersionId, UUID assignedMembershipId);

    /** The contact's open lead, if the caller can see it: an admin any, a staff member only their own. */
    Optional<UUID> visibleOpenLead(UUID contactId);

    /** The lead, if the caller can see it: not a deleted one (step 3.3b). */
    Optional<LeadView> lead(UUID leadId);

    /**
     * A lead as the leads API answers with it (steps 2.10 and 3.3b). conversationId, assignedMembershipId, previousStage
     * (set only in Needs Human) and closedAt (set only in a closing stage) may be null. The stage names come from the
     * lead's own version, a reserved stage's default when it has none; they are null for a version the engine cannot
     * run.
     */
    record LeadView(
            UUID id,
            UUID contactId,
            UUID conversationId,
            UUID workflowVersionId,
            String stage,
            String stageName,
            String previousStage,
            String previousStageName,
            Instant stageEnteredAt,
            Instant closedAt,
            int version,
            String origin,
            UUID assignedMembershipId,
            Instant createdAt) {

        public LeadView {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contactId, "contactId");
        }
    }
}
