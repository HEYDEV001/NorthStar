package com.northstar.workflow;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * How an inbound message finds its lead (step 2.4b, W1, W2, P3): the contact's open lead, or a new one. Every method
 * runs only inside the caller's transaction (none is opened here) and only as the system actor, in the current tenant.
 * The caller holds the contact's row lock, so one contact's lead decisions never race; the one-open-lead-per-contact
 * index is the backstop.
 *
 * <p>Nothing here reads the workflow config: every lead is created in stage {@value #FIRST_STAGE} (Phase 3 moves it).
 */
public interface LeadIntake {

    /** The stage every lead is created in (workflow-spec.md section 3; decided 20 Nov 2026, so 3.1 requires it). */
    String FIRST_STAGE = "new";

    /** The contact's open lead: not closed and not deleted (at most one, data-model.md section 13). */
    Optional<OpenLead> findOpenLead(UUID contactId);

    /**
     * The tenant's published workflow version for new leads: the current version of its one workflow that has one.
     * Empty when no workflow is published, or when more than one is (one active workflow per tenant for MVP).
     */
    Optional<UUID> publishedWorkflowVersion();

    /**
     * Links the open lead to its contact's conversation if it has none yet (a lead entered without one), adding 1 to its
     * version; a lead already linked is left as it is. Returns the lead as it now is.
     */
    OpenLead linkConversation(OpenLead lead, UUID conversationId);

    /**
     * Opens a lead for the contact in stage {@value #FIRST_STAGE}. If the contact already has an open lead (a writer
     * that does not hold the contact's lock), that lead is returned instead, with {@code created} false, and linked to
     * the conversation if it has none.
     */
    OpenLead open(NewLead lead);

    /** An open lead: its conversation (null if none linked) and assignee (null: unassigned). */
    record OpenLead(UUID id, UUID conversationId, UUID assignedMembershipId, boolean created) {

        public OpenLead {
            Objects.requireNonNull(id, "id");
        }
    }

    /** A lead to open; the assignee is the conversation's (null: unassigned). */
    record NewLead(
            UUID contactId, UUID conversationId, UUID workflowVersionId, LeadOrigin origin, UUID assignedMembershipId) {

        public NewLead {
            Objects.requireNonNull(contactId, "contactId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(workflowVersionId, "workflowVersionId");
            Objects.requireNonNull(origin, "origin");
        }
    }
}
