package com.northstar.integrations;

import com.northstar.workflow.ActionOutcome;

/**
 * Sends one email (integration-specs.md section 6). The only way any module sends email; nothing outside integrations
 * calls Resend. Called from an action handler: the idempotency key is the action's, so a retried send is not sent
 * twice (Resend keeps keys for 24 hours). The answer is already classified retryable, permanent or auth
 * (integration-specs.md section 1). Adapters log status codes and provider error names only, never addresses, subjects
 * or bodies.
 */
public interface EmailSender {

    ActionOutcome send(OutgoingEmail email, String idempotencyKey);
}
