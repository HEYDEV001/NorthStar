package com.northstar.workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Side effects as actions (workflow-spec.md sections 9 and 10, step 1.10). Every method runs in the caller's tenant
 * scope; another tenant's action is the same as a missing one.
 */
public interface Actions {

    /** The action, created now or already there under the same idempotency key. */
    record Created(ActionView action, boolean created) {}

    /** Creates the action, or returns the existing one with the same idempotency key. A pending one is queued. */
    Created create(NewAction action);

    Optional<ActionView> find(UUID actionId);

    /**
     * Approves a needs_review action so it runs; approving one that is no longer waiting changes nothing. Needs a
     * member: the system actor cannot approve. Which role may approve which type comes with the approval screens
     * (Phase 3).
     */
    ActionView approve(UUID actionId);

    /** Failed actions, most recent first: the Failed Actions screen's list. */
    List<ActionView> failed();

    /** Resumes this type's paused actions once their integration reconnects (the trigger lands in Phase 2 / 7). */
    int resumePaused(String type);
}
