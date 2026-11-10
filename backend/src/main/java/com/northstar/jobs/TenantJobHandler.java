package com.northstar.jobs;

import com.northstar.api.LogContext;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import java.util.UUID;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs a {@link TenantJobRequest} inside its tenant's scope as the system actor (step 1.10, P10). Before the job's own
 * work, it checks that the request carries a tenant and that the tenant exists; otherwise the job fails loudly (an
 * ERROR event and a failed job, never retried) and nothing runs with an empty or guessed context.
 */
public abstract class TenantJobHandler<T extends TenantJobRequest> implements JobRequestHandler<T> {

    private static final Logger log = LoggerFactory.getLogger(TenantJobHandler.class);

    private final TenantDirectory tenants;

    protected TenantJobHandler(TenantDirectory tenants) {
        this.tenants = tenants;
    }

    @Override
    public final void run(T request) {
        UUID tenantId = request == null ? null : request.tenantId();
        if (tenantId == null) {
            log.error("job.tenant_missing job_id={} job_type={}", jobId(), typeOf(request));
            throw new JobTenantException("Job has no tenant");
        }
        MDC.put(LogContext.TENANT_ID, tenantId.toString());
        MDC.put(LogContext.USER_ID, TenantContext.SYSTEM_USER_ID.toString());
        try {
            TenantScope.run(TenantContext.system(tenantId), () -> {
                if (!tenants.currentTenantExists()) {
                    log.error("job.tenant_unknown job_id={} job_type={}", jobId(), typeOf(request));
                    throw new JobTenantException("Job's tenant does not exist");
                }
                runForTenant(request);
            });
        } finally {
            MDC.remove(LogContext.TENANT_ID);
            MDC.remove(LogContext.USER_ID);
        }
    }

    /** The job's work, inside the tenant scope. Transactions opened here run as app.role = 'system'. */
    protected abstract void runForTenant(T request);

    private UUID jobId() {
        try {
            return jobContext().getJobId();
        } catch (RuntimeException outsideAJob) {
            return null;
        }
    }

    private static String typeOf(Object request) {
        return request == null ? null : request.getClass().getSimpleName();
    }
}
