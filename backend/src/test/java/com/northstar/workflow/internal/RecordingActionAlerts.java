package com.northstar.workflow.internal;

import com.northstar.workflow.ActionAlerts;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Test-only: records every alert as "kind|action|attempts|class|code" instead of logging it. */
@Component
@Primary
class RecordingActionAlerts implements ActionAlerts {

    private final List<String> alerts = new CopyOnWriteArrayList<>();

    List<String> of(UUID actionId) {
        return alerts.stream()
                .filter(alert -> alert.contains("|" + actionId + "|"))
                .toList();
    }

    @Override
    public void failedRepeatedly(
            UUID tenantId, UUID actionId, String type, int attempts, String errorClass, String code) {
        alerts.add("failed_repeatedly|" + actionId + "|" + attempts + "|" + errorClass + "|" + code);
    }

    @Override
    public void failed(UUID tenantId, UUID actionId, String type, int attempts, String errorClass, String code) {
        alerts.add("failed|" + actionId + "|" + attempts + "|" + errorClass + "|" + code);
    }

    @Override
    public void authFailed(UUID tenantId, UUID actionId, String type, String code) {
        alerts.add("auth_failed|" + actionId + "|" + code);
    }
}
