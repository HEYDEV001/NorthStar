package com.northstar.workflow.internal;

import com.northstar.jobs.Jobs;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionAlerts;
import com.northstar.workflow.ClosedAction;
import com.northstar.workflow.DependentActions;
import com.northstar.workflow.FailureClass;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DependentActions} (step 2.8c). The actions are found by a native query with bind parameters and an explicit
 * tenant filter on top of RLS (D7), locked in ID order, and changed through {@link Action}'s own lifecycle methods, as
 * the executor changes them. MANDATORY: it joins the caller's transaction, which already holds the locks that come
 * before the actions' (see the interface), so what it changes commits with the caller's change or not at all.
 *
 * <p>Jobs and alerts are created only once that transaction commits. Logs carry action IDs, types and fixed codes only.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class DependentActionsService implements DependentActions {

    private static final Logger log = LoggerFactory.getLogger(DependentActionsService.class);

    private static final List<String> RUNNABLE = List.of(Action.PENDING);
    private static final List<String> OPEN = List.of(Action.PENDING, Action.NEEDS_REVIEW, Action.PAUSED);

    private final ActionRepository actions;
    private final ObjectProvider<ActionHandlers> handlers;
    private final ActionAlerts alerts;
    private final Jobs jobs;

    /**
     * The handlers are looked up when an action fails, not when this is built: an action handler can be what calls this
     * (the WhatsApp sync applies template statuses, step 2.8c), and the handlers are built from every handler.
     */
    DependentActionsService(
            ActionRepository actions, ObjectProvider<ActionHandlers> handlers, ActionAlerts alerts, Jobs jobs) {
        this.actions = actions;
        this.handlers = handlers;
        this.alerts = alerts;
        this.jobs = jobs;
    }

    @Override
    public int block(Dependents dependents, String code) {
        UUID tenant = systemTenant();
        requireCode(code);
        if (dependents.values().isEmpty()) return 0;
        List<Action> found = actions.findDependentsLocked(
                tenant, dependents.type(), RUNNABLE, dependents.field(), dependents.values());
        for (Action action : found) {
            action.block(code);
            log.info(
                    "action.blocked action_id={} type={} code={} at=dependency",
                    action.getId(),
                    action.getType(),
                    code);
        }
        return found.size();
    }

    @Override
    public int unblock(Dependents dependents, String code) {
        UUID tenant = systemTenant();
        requireCode(code);
        if (dependents.values().isEmpty()) return 0;
        List<Action> found = actions.findBlockedDependentsLocked(
                tenant, dependents.type(), code, dependents.field(), dependents.values());
        Instant now = ActionExecutor.now();
        for (Action action : found) {
            action.unblock(now);
            ExecuteAction job = new ExecuteAction(tenant, action.getId());
            // A kept time still ahead gives the job ID it was first scheduled with, so that job runs it and no other.
            if (action.getNextAttemptAt().isAfter(now)) {
                jobs.schedule(ActionExecutor.jobIdFor(action), action.getNextAttemptAt(), job);
            } else {
                jobs.enqueue(ActionExecutor.jobIdFor(action), job);
            }
            log.info(
                    "action.unblocked action_id={} type={} code={} next_attempt_at={}",
                    action.getId(),
                    action.getType(),
                    code,
                    action.getNextAttemptAt());
        }
        return found.size();
    }

    @Override
    public int fail(Dependents dependents, String code) {
        UUID tenant = systemTenant();
        requireCode(code);
        if (dependents.values().isEmpty()) return 0;
        List<Action> found =
                actions.findDependentsLocked(tenant, dependents.type(), OPEN, dependents.field(), dependents.values());
        String permanent = FailureClass.PERMANENT.databaseValue();
        for (Action action : found) {
            action.fail(permanent, code);
            handlers.getObject()
                    .reportClosed(
                            action.getId(), action.getType(), action.getPayload(), ClosedAction.Closing.FAILED, code);
            UUID id = action.getId();
            String type = action.getType();
            int attempts = action.getAttempts();
            ActionExecutor.afterCommit(() -> alerts.failed(tenant, id, type, attempts, permanent, code));
        }
        return found.size();
    }

    @Override
    public int cancel(Dependents dependents, String reason) {
        UUID tenant = cancellingTenant();
        requireCode(reason);
        if (dependents.values().isEmpty()) return 0;
        List<Action> found =
                actions.findDependentsLocked(tenant, dependents.type(), OPEN, dependents.field(), dependents.values());
        for (Action action : found) {
            action.cancel(reason);
            handlers.getObject()
                    .reportClosed(
                            action.getId(),
                            action.getType(),
                            action.getPayload(),
                            ClosedAction.Closing.CANCELLED,
                            reason);
            log.info(
                    "action.cancelled action_id={} type={} reason={} at=dependency",
                    action.getId(),
                    action.getType(),
                    reason);
        }
        return found.size();
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("A fixed code");
    }

    /** The system, or since step 2.9c an admin or staff member whose own request cancels them (a take over). */
    private static UUID cancellingTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem() && context.role() != Role.ADMIN && context.role() != Role.STAFF) {
            throw new IllegalStateException("Dependent actions are cancelled by the system actor, admins and staff");
        }
        return Objects.requireNonNull(context.tenantId());
    }

    private static UUID systemTenant() {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) throw new IllegalStateException("Dependent actions are changed by the system actor");
        return Objects.requireNonNull(context.tenantId());
    }
}
