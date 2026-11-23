package com.northstar.workflow;

import java.util.Objects;
import java.util.Set;

/**
 * Brings the actions that depend on something outside workflow in line with it (step 2.8c, W34): a WhatsApp template's
 * sends follow the template's status. The caller names the actions by type and by one field of their payload (a send's
 * {@code messageId}), so workflow never reads the caller's tables. Every method runs only inside the caller's
 * transaction (none is opened here), in the current tenant, and as the system actor, except {@link #cancel}, which an
 * admin or a staff member may call too (step 2.9c: taking a conversation over); another tenant's actions are never
 * found.
 *
 * <p>Lock order: the caller first holds the lock on what the actions depend on (the template) and shared locks on their
 * recipients (contacts, in ID order), as an opt-out locks the contact and then its actions; these methods then lock the
 * actions, in ID order, and {@link #fail} reaches each handler's {@link ActionHandler#closed}, which locks what the
 * action wrote (its message). An action that is processing an attempt is never changed: the attempt's own outcome
 * decides, and the next call catches it.
 *
 * <p>Calling any method again changes nothing more (P1).
 */
public interface DependentActions {

    /**
     * The actions of one type whose payload's {@code field} holds one of {@code values}, compared as text.
     *
     * @param field a payload field name: a letter, then letters, digits or underscores, at most 64 characters
     */
    record Dependents(String type, String field, Set<String> values) {
        public Dependents {
            Objects.requireNonNull(type, "type");
            if (field == null || !field.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("A payload field is a plain name");
            }
            values = Set.copyOf(values);
        }
    }

    /**
     * Pauses the pending ones (due now, scheduled for later, or waiting for a retry) as {@link FailureClass#BLOCKED}
     * with this code, keeping their attempts and the time they were due. Needs-review, paused and processing ones are
     * left as they are: a needs-review one waits for its approval and is blocked when it runs. Returns how many.
     */
    int block(Dependents dependents, String code);

    /**
     * Resumes the ones paused as blocked with this code: pending again at the later of the time they were due and now,
     * and queued for then. Auth pauses, and blocks with another code, stay paused. Returns how many.
     */
    int unblock(Dependents dependents, String code);

    /**
     * Fails for good, as permanent with this code, the pending, needs-review and paused ones (auth and blocked pauses
     * included), as any final failure: each is reported to its handler's {@link ActionHandler#closed} in this
     * transaction, alerted ({@link ActionAlerts#failed}) once it commits, and listed on the Failed Actions screen.
     * Returns how many.
     */
    int fail(Dependents dependents, String code);

    /**
     * Cancels, with this reason, the pending, needs-review and paused ones (auth and blocked pauses included), as an
     * opt-out cancels a contact's: each is reported to its handler's {@link ActionHandler#closed} in this transaction,
     * raises no alert, and its queued job later finds it cancelled and does nothing. Processing ones are left to their
     * attempt. Step 2.9c: taking a conversation over cancels its queued automated sends as
     * {@code conversation_taken_over}. Returns how many.
     */
    int cancel(Dependents dependents, String reason);
}
