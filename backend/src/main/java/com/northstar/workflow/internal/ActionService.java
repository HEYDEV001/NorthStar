package com.northstar.workflow.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.jobs.Jobs;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link Actions}: creating, approving, listing and resuming actions in the current tenant. */
@Service
class ActionService implements Actions {

    private final ActionRepository actions;
    private final ActionHandlers handlers;
    private final TenantDirectory tenants;
    private final AuditTrail audit;
    private final Jobs jobs;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    ActionService(
            ActionRepository actions,
            ActionHandlers handlers,
            TenantDirectory tenants,
            AuditTrail audit,
            Jobs jobs,
            PlatformTransactionManager transactionManager) {
        this.actions = actions;
        this.handlers = handlers;
        this.tenants = tenants;
        this.audit = audit;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Created create(NewAction request) {
        TenantContext context = TenantScope.require();
        if (handlers.get(request.type()).isEmpty()) {
            throw new IllegalArgumentException("No handler for action type " + request.type());
        }
        return transactions.execute(status -> {
            Instant now = ActionExecutor.now();
            String initialStatus = request.needsReview() ? Action.NEEDS_REVIEW : Action.PENDING;
            int inserted = actions.insertIfAbsent(
                    UUID.randomUUID(),
                    context.tenantId(),
                    request.leadId(),
                    request.type(),
                    initialStatus,
                    request.idempotencyKey(),
                    request.mode().databaseValue(),
                    request.payloadJson(),
                    request.needsReview() ? null : now,
                    request.stageEntryId());
            Action action =
                    actions.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
            if (inserted == 1 && Action.PENDING.equals(action.getStatus())) queue(action);
            return new Created(action.view(), inserted == 1);
        });
    }

    @Override
    public Optional<ActionView> find(UUID actionId) {
        TenantScope.require();
        return readOnly.execute(status -> actions.findById(actionId).map(Action::view));
    }

    @Override
    public ActionView approve(UUID actionId) {
        if (TenantScope.require().isSystem()) throw new IllegalStateException("The system actor cannot approve");
        UUID approver = tenants.currentMembershipId()
                .orElseThrow(() -> new ForbiddenException("no_active_membership", "This account has no membership"));
        return transactions.execute(status -> {
            Action action =
                    actions.findLockedById(actionId).orElseThrow(() -> new NotFoundException("action", actionId));
            if (!Action.NEEDS_REVIEW.equals(action.getStatus())) return action.view();
            action.approve(approver, ActionExecutor.now());
            audit.recordChange("action.approved", "actions", actionId, Action.NEEDS_REVIEW, Action.PENDING);
            queue(action);
            return action.view();
        });
    }

    @Override
    public List<ActionView> failed() {
        TenantScope.require();
        return readOnly.execute(status -> actions.findByStatusOrderByLastAttemptAtDesc(Action.FAILED).stream()
                .map(Action::view)
                .toList());
    }

    @Override
    public int resumePaused(String type) {
        TenantScope.require();
        return transactions.execute(status -> {
            List<Action> paused = actions.findByStatusAndType(Action.PAUSED, type);
            Instant now = ActionExecutor.now();
            for (Action action : paused) {
                action.resume(now);
                queue(action);
            }
            return paused.size();
        });
    }

    /** Queues the action's next attempt; the job is created after this transaction commits. */
    private void queue(Action action) {
        jobs.enqueue(ActionExecutor.jobIdFor(action), new ExecuteAction(action.getTenantId(), action.getId()));
    }
}
