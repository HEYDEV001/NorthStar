package com.northstar.workflow.internal;

import com.northstar.api.ForbiddenException;
import com.northstar.api.NotFoundException;
import com.northstar.jobs.Jobs;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionRecipient;
import com.northstar.workflow.ActionView;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import com.northstar.workflow.OutboundCancellation;
import com.northstar.workflow.RecipientConsent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link Actions}: creating, approving, listing and resuming actions in the current tenant.
 *
 * <p>An action that sends something to a contact names that contact, and one for a contact who has opted out is
 * created cancelled ({@value OutboundCancellation#CONTACT_OPTED_OUT}) and never queued (step 2.5, W32). The consent is
 * read under the contact's shared lock in the creating transaction, so an opt-out being recorded at the same time is
 * waited for and seen; the database refuses any runnable insert for such a contact as the backstop (NSA01).
 */
@Service
class ActionService implements Actions {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);

    private final ActionRepository actions;
    private final ActionHandlers handlers;
    private final RecipientConsent consent;
    private final TenantDirectory tenants;
    private final AuditTrail audit;
    private final Jobs jobs;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;

    ActionService(
            ActionRepository actions,
            ActionHandlers handlers,
            RecipientConsent consent,
            TenantDirectory tenants,
            AuditTrail audit,
            Jobs jobs,
            PlatformTransactionManager transactionManager) {
        this.actions = actions;
        this.handlers = handlers;
        this.consent = consent;
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
        ActionHandler handler = handlers.get(request.type())
                .orElseThrow(() -> new IllegalArgumentException("No handler for action type " + request.type()));
        boolean toContact = handler.recipient() == ActionRecipient.CONTACT;
        if (toContact != (request.recipientContactId() != null)) {
            throw new IllegalArgumentException(
                    toContact
                            ? "Action type " + request.type() + " messages a contact and needs its recipient"
                            : "Action type " + request.type() + " messages no contact and takes no recipient");
        }
        return transactions.execute(status -> {
            Instant now = ActionExecutor.now();
            boolean optedOut = toContact && consent.isOptedOut(request.recipientContactId());
            String initialStatus;
            if (optedOut) initialStatus = Action.CANCELLED;
            else if (request.needsReview()) initialStatus = Action.NEEDS_REVIEW;
            else initialStatus = Action.PENDING;
            int inserted = actions.insertIfAbsent(
                    UUID.randomUUID(),
                    context.tenantId(),
                    request.leadId(),
                    request.type(),
                    initialStatus,
                    request.idempotencyKey(),
                    request.mode().databaseValue(),
                    request.payloadJson(),
                    Action.PENDING.equals(initialStatus) ? now : null,
                    request.stageEntryId(),
                    request.recipientContactId(),
                    optedOut ? OutboundCancellation.CONTACT_OPTED_OUT : null);
            Action action =
                    actions.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
            if (inserted == 1 && Action.PENDING.equals(action.getStatus())) queue(action);
            if (inserted == 1 && optedOut) {
                log.info(
                        "action.cancelled action_id={} type={} reason={} at=create",
                        action.getId(),
                        action.getType(),
                        OutboundCancellation.CONTACT_OPTED_OUT);
            }
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
        TenantContext context = TenantScope.require();
        if (context.isSystem()) throw new IllegalStateException("The system actor cannot approve");
        // Admins only until Phase 3 sets per-role approval rules (workflow-spec.md section 9).
        if (context.role() != Role.ADMIN) throw new ForbiddenException("admin_only", "Only an admin can do this");
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
