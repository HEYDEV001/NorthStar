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

    RetryPolicy retryPolicy();

    Duration timeout();

    ActionOutcome execute(ActionRequest request);
}
