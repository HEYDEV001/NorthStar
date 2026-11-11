package com.northstar.workflow;

import java.util.UUID;

/**
 * Where action failures are reported (workflow-spec.md section 10). Each fires at most once per action, after the
 * transaction that recorded the failure commits. For now the implementation logs a structured event; notifying the
 * business admin lands with email (step 1.12) and WhatsApp (Phase 2), the Northstar admin's cross-tenant view in
 * Phase 8, and the integration status change for auth failures in Phase 2 / 7.
 */
public interface ActionAlerts {

    /** The action's third failed attempt, when it will still be retried. If it was the last, only {@link #failed}. */
    void failedRepeatedly(UUID tenantId, UUID actionId, String type, int attempts, String errorClass, String code);

    /** The action failed for good: permanent, or out of attempts. It is now on the Failed Actions screen. */
    void failed(UUID tenantId, UUID actionId, String type, int attempts, String errorClass, String code);

    /** The action paused on an auth failure; its integration needs reconnecting. */
    void authFailed(UUID tenantId, UUID actionId, String type, String code);
}
