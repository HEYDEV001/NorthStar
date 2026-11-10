package com.northstar.jobs;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only way to create jobs (step 1.10). Each job has a caller-chosen ID: JobRunr skips a job whose ID already
 * exists, so enqueueing the same work twice creates one job. Inside a transaction the job is created only after the
 * commit, so a worker never picks up a job for rows it cannot see yet, and a rollback creates no job.
 *
 * <p>Inside a tenant scope, a job may only be created for that same tenant.
 */
public class Jobs {

    private final JobRequestScheduler scheduler;

    Jobs(JobRequestScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** A stable job ID from its parts, e.g. ("execute-action", actionId, attempt). */
    public static UUID jobId(Object... parts) {
        StringBuilder name = new StringBuilder();
        for (Object part : parts)
            name.append(Objects.requireNonNull(part, "job ID part")).append('/');
        return UUID.nameUUIDFromBytes(name.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void enqueue(UUID jobId, TenantJobRequest request) {
        check(jobId, request);
        afterCommit(() -> scheduler.enqueue(jobId, request));
    }

    public void schedule(UUID jobId, Instant at, TenantJobRequest request) {
        check(jobId, request);
        Objects.requireNonNull(at, "at");
        afterCommit(() -> scheduler.schedule(jobId, at, request));
    }

    private static void check(UUID jobId, TenantJobRequest request) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(request, "request");
        if (request.tenantId() == null) throw new JobTenantException("A tenant job needs a tenant");
        Optional<TenantContext> scope = TenantScope.current();
        if (scope.isPresent() && !scope.get().tenantId().equals(request.tenantId())) {
            throw new JobTenantException("A job may only be created for the current tenant");
        }
    }

    private static void afterCommit(Runnable create) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            create.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                create.run();
            }
        });
    }
}
