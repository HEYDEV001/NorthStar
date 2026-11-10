package com.northstar.workflow.internal;

import com.northstar.workflow.ActionHandler;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Every {@link ActionHandler} bean by type. Checked at startup: one handler per type, and each handler's timeout shorter
 * than the processing lease, so an attempt is always recorded before its lease could expire and be taken over.
 */
@Component
class ActionHandlers {

    private final Map<String, ActionHandler> byType = new HashMap<>();
    private final Duration lease;

    ActionHandlers(List<ActionHandler> handlers, @Value("${northstar.actions.lease}") Duration lease) {
        this.lease = lease;
        for (ActionHandler handler : handlers) {
            Duration timeout = handler.timeout();
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(lease) >= 0) {
                throw new IllegalStateException(
                        "Action handler " + handler.type() + " needs a timeout shorter than the lease");
            }
            if (byType.putIfAbsent(handler.type(), handler) != null) {
                throw new IllegalStateException("Two action handlers for " + handler.type());
            }
        }
    }

    Optional<ActionHandler> get(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    Duration lease() {
        return lease;
    }
}
