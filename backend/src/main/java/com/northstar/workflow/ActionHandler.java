package com.northstar.workflow;

import java.time.Duration;

/**
 * Performs one action type's side effect (step 1.10). Implementations live in the module that owns the provider and
 * are Spring beans; the executor finds them by {@link #type()}.
 *
 * <p>The executor calls {@link #execute} outside any transaction, on its own thread inside the action's tenant scope,
 * and gives up after {@link #timeout()}, recording the attempt as retryable "timeout". The timeout must be shorter
 * than the processing lease (northstar.actions.lease), which is checked at startup, so a slow attempt is always
 * recorded before another worker could take the action over. A handler that throws is recorded as retryable
 * "unexpected_error".
 */
public interface ActionHandler {

    /** The action type, e.g. "whatsapp.send". */
    String type();

    /**
     * Whom this type sends something to (step 2.5). There is no default, so every handler must decide: a
     * {@link ActionRecipient#CONTACT} type is created only with its recipient, and is refused once that contact has
     * opted out (W32).
     */
    ActionRecipient recipient();

    RetryPolicy retryPolicy();

    Duration timeout();

    ActionOutcome execute(ActionRequest request);

    /**
     * Called when one of this type's actions closes without succeeding (step 2.6a): it failed for good, or was cancelled
     * (its recipient opted out), wherever that happens: at creation, at claim, by the opt-out, or when its last attempt
     * fails or its lease runs out. Many of these never call {@link #execute}, so this is the one place a handler hears
     * of them; a success is not reported here, since {@link #execute} already knows.
     *
     * <p>It runs inside the transaction that records the closing, as that transaction's actor and tenant, so whatever it
     * writes commits or rolls back with the action's new status, and an exception rolls both back. It must not call a
     * provider, open its own transaction or take a lock on another action. The default does nothing.
     */
    default void closed(ClosedAction action) {}
}
