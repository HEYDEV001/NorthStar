package com.northstar.workflow.internal;

import com.northstar.workflow.ClosedAction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-only (step 2.6a): what the fake action handlers were told through {@link
 * com.northstar.workflow.ActionHandler#closed}, as "closing|code|in_transaction", and a switch that makes the hook throw
 * for one action, to show that its failure rolls the closing back.
 */
final class ClosedActionRecorder {

    private final List<ClosedAction> closed = new CopyOnWriteArrayList<>();
    private final List<String> entries = new CopyOnWriteArrayList<>();
    private final Set<UUID> failing = ConcurrentHashMap.newKeySet();

    void record(ClosedAction action) {
        if (failing.contains(action.actionId())) throw new IllegalStateException("Closing hook failure (test)");
        closed.add(action);
        entries.add(action.actionId() + "|"
                + action.closing().name().toLowerCase(java.util.Locale.ROOT) + "|"
                + action.code() + "|"
                + TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** "closing|code|in_transaction" for each time the action was reported, in order. */
    List<String> of(UUID actionId) {
        String prefix = actionId + "|";
        return entries.stream()
                .filter(entry -> entry.startsWith(prefix))
                .map(entry -> entry.substring(prefix.length()))
                .toList();
    }

    List<ClosedAction> all(UUID actionId) {
        return closed.stream().filter(a -> a.actionId().equals(actionId)).toList();
    }

    void failFor(UUID actionId) {
        failing.add(actionId);
    }

    void stopFailingFor(UUID actionId) {
        failing.remove(actionId);
    }
}
