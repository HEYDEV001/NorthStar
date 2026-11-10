package com.northstar.workflow.internal;

import com.northstar.workflow.ActionAlerts;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Alerts as structured log events, IDs and codes only, until notifications exist (see {@link ActionAlerts}). */
@Component
class LoggingActionAlerts implements ActionAlerts {

    private static final Logger log = LoggerFactory.getLogger(LoggingActionAlerts.class);

    @Override
    public void failedRepeatedly(
            UUID tenantId, UUID actionId, String type, int attempts, String errorClass, String code) {
        log.warn(
                "action.failed_repeatedly tenant_id={} action_id={} type={} attempts={} error_class={} error_code={}",
                tenantId,
                actionId,
                type,
                attempts,
                errorClass,
                code);
    }

    @Override
    public void failed(UUID tenantId, UUID actionId, String type, int attempts, String errorClass, String code) {
        log.error(
                "action.failed tenant_id={} action_id={} type={} attempts={} error_class={} error_code={}",
                tenantId,
                actionId,
                type,
                attempts,
                errorClass,
                code);
    }

    @Override
    public void authFailed(UUID tenantId, UUID actionId, String type, String code) {
        log.warn("action.auth_failed tenant_id={} action_id={} type={} error_code={}", tenantId, actionId, type, code);
    }
}
