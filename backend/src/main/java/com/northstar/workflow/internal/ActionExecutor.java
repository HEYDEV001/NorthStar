package com.northstar.workflow.internal;

import com.northstar.jobs.Jobs;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionAlerts;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.FailureClass;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one attempt at an action (workflow-spec.md section 10, step 1.10), always inside the action's tenant scope.
 *
 * <ol>
 *   <li>Claim, in a short transaction holding the row lock: only a due action is taken (pending and past its next
 *       attempt, or processing with an expired lease, when the lost attempt still counts). Anything else, a succeeded
 *       action included, is left alone, so running the same job twice performs the side effect once.
 *   <li>Call the handler outside any transaction, on its own thread, for at most its timeout.
 *   <li>Record the outcome, in a second short transaction, only if the action is still on this attempt.
 * </ol>
 *
 * <p>Alerts and the next attempt's job are created only after the recording transaction commits.
 */
@Service
class ActionExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    /** The third failed attempt raises an alert, unless it is also the last (workflow-spec.md section 10). */
    static final int REPEATED_FAILURE_ALERT_AT = 3;

    private final ActionRepository actions;
    private final ActionHandlers handlers;
    private final ActionAlerts alerts;
    private final Jobs jobs;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readOnly;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    ActionExecutor(
            ActionRepository actions,
            ActionHandlers handlers,
            ActionAlerts alerts,
            Jobs jobs,
            PlatformTransactionManager transactionManager) {
        this.actions = actions;
        this.handlers = handlers;
        this.alerts = alerts;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** One claimed attempt. */
    private record Claim(Action action, ActionHandler handler, ActionRequest request) {}

    void execute(UUID actionId) {
        TenantContext context = TenantScope.require();
        Claim claim = transactions.execute(status -> claim(actionId));
        if (claim == null) return;
        ActionOutcome outcome = attempt(claim, context);
        transactions.executeWithoutResult(status -> record(claim, outcome));
    }

    /** Queues a job for each of the current tenant's due actions. Job IDs repeat for the same due time. */
    void queueDue() {
        UUID tenantId = TenantScope.require().tenantId();
        readOnly.executeWithoutResult(status -> {
            for (Action action : actions.findDue(now())) {
                jobs.enqueue(jobIdFor(action), new ExecuteAction(tenantId, action.getId()));
            }
        });
    }

    /**
     * A job ID per dispatch: the action and the time it is due (or its lease expiry, for a takeover). Queueing the same
     * dispatch twice creates one job; a resumed or retried action gets a new one.
     */
    static UUID jobIdFor(Action action) {
        if (Action.PROCESSING.equals(action.getStatus())) {
            return Jobs.jobId(
                    "take-over-action",
                    action.getId(),
                    action.getLeaseExpiresAt().toEpochMilli());
        }
        return Jobs.jobId(
                "execute-action", action.getId(), action.getNextAttemptAt().toEpochMilli());
    }

    /** Microseconds, as Postgres stores them, so a time read back gives the same job ID as the one written. */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Claim claim(UUID actionId) {
        Optional<Action> found = actions.findLockedById(actionId);
        if (found.isEmpty()) {
            log.info("Not found: action {}", actionId); // another tenant's action looks the same (P9, P10)
            return null;
        }
        Action action = found.get();
        Instant now = now();
        boolean takeover = Action.PROCESSING.equals(action.getStatus())
                && !action.getLeaseExpiresAt().isAfter(now);
        boolean due = Action.PENDING.equals(action.getStatus())
                && !action.getNextAttemptAt().isAfter(now);
        if (!due && !takeover) return null;

        Optional<ActionHandler> handler = handlers.get(action.getType());
        if (handler.isEmpty()) {
            finalFailure(action, FailureClass.PERMANENT, "no_handler");
            return null;
        }
        if (takeover) {
            log.warn("action.lease_expired action_id={} attempt={}", actionId, action.getAttempts());
            if (action.getAttempts() >= handler.get().retryPolicy().maxAttempts()) {
                finalFailure(action, FailureClass.RETRYABLE, "lease_expired");
                return null;
            }
        }
        action.claim(now, now.plus(handlers.lease()));
        ActionRequest request = new ActionRequest(
                action.getTenantId(),
                actionId,
                action.getType(),
                action.getIdempotencyKey(),
                action.getPayload(),
                action.getAttempts());
        return new Claim(action, handler.get(), request);
    }

    /**
     * Calls the handler on its own thread, in the same tenant scope, and waits at most its timeout. A late handler is
     * interrupted and the attempt recorded as retryable "timeout", well before the lease could expire.
     */
    private ActionOutcome attempt(Claim claim, TenantContext context) {
        UUID actionId = claim.request().actionId();
        Future<ActionOutcome> call = calls.submit(
                () -> TenantScope.call(context, () -> claim.handler().execute(claim.request())));
        try {
            ActionOutcome outcome = call.get(claim.handler().timeout().toMillis(), TimeUnit.MILLISECONDS);
            return outcome != null ? outcome : ActionOutcome.retryable("no_outcome");
        } catch (TimeoutException e) {
            call.cancel(true);
            log.warn(
                    "action.timeout action_id={} attempt={}",
                    actionId,
                    claim.request().attempt());
            return ActionOutcome.retryable("timeout");
        } catch (ExecutionException e) {
            // The exception's class only: its message could carry provider responses or personal data.
            log.warn(
                    "action.handler_error action_id={} attempt={} exception={}",
                    actionId,
                    claim.request().attempt(),
                    e.getCause().getClass().getName());
            return ActionOutcome.retryable("unexpected_error");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return ActionOutcome.retryable("interrupted");
        }
    }

    private void record(Claim claim, ActionOutcome outcome) {
        UUID actionId = claim.request().actionId();
        int attempt = claim.request().attempt();
        Action action = actions.findLockedById(actionId).orElseThrow();
        if (!Action.PROCESSING.equals(action.getStatus()) || action.getAttempts() != attempt) {
            log.warn("action.attempt_superseded action_id={} attempt={}", actionId, attempt);
            return;
        }
        switch (outcome) {
            case ActionOutcome.Succeeded succeeded -> action.succeed(succeeded.resultJson());
            case ActionOutcome.Failed failed -> recordFailure(claim, action, failed);
        }
    }

    private void recordFailure(Claim claim, Action action, ActionOutcome.Failed failed) {
        int attempt = action.getAttempts();
        String errorClass = failed.failureClass().databaseValue();
        switch (failed.failureClass()) {
            case AUTH -> {
                action.pause(failed.code());
                afterCommit(
                        () -> alerts.authFailed(action.getTenantId(), action.getId(), action.getType(), failed.code()));
                return;
            }
            case PERMANENT -> finalFailure(action, failed.failureClass(), failed.code());
            case RETRYABLE -> {
                Instant now = now();
                Instant next = claim.handler()
                        .retryPolicy()
                        .nextAttemptAt(attempt, now, action.runnableSince(), failed.retryAfter());
                if (next == null) {
                    finalFailure(action, failed.failureClass(), failed.code());
                } else {
                    next = next.truncatedTo(ChronoUnit.MICROS);
                    action.retryAt(next, errorClass, failed.code());
                    jobs.schedule(jobIdFor(action), next, new ExecuteAction(action.getTenantId(), action.getId()));
                }
            }
        }
        // Only the final-failure alert when the third attempt is also the last.
        if (attempt == REPEATED_FAILURE_ALERT_AT && !Action.FAILED.equals(action.getStatus())) {
            afterCommit(() -> alerts.failedRepeatedly(
                    action.getTenantId(), action.getId(), action.getType(), attempt, errorClass, failed.code()));
        }
    }

    private void finalFailure(Action action, FailureClass failureClass, String code) {
        action.fail(failureClass.databaseValue(), code);
        int attempts = action.getAttempts();
        afterCommit(() -> alerts.failed(
                action.getTenantId(), action.getId(), action.getType(), attempts, failureClass.databaseValue(), code));
    }

    /** Runs once the current transaction commits; never if it rolls back, so an alert fires at most once. */
    private static void afterCommit(Runnable work) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }

    @Override
    public void destroy() {
        calls.shutdownNow();
    }
}
