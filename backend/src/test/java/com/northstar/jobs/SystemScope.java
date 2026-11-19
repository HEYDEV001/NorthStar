package com.northstar.jobs;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Test-only: runs work as the worker's system actor in a tenant, as a job handler does on a worker thread. The system
 * context can be created only from the jobs package (step 1.10), and never on a thread serving a web request. Spring's
 * test support binds mock request attributes to the test thread, so they are lifted for the duration of the work and
 * put back afterwards: the work runs as it would on a worker thread, with no request.
 */
public final class SystemScope {

    private SystemScope() {}

    public static <T> T call(UUID tenantId, Supplier<T> work) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.resetRequestAttributes();
        try {
            return TenantScope.call(TenantContext.system(tenantId), work);
        } finally {
            if (request != null) RequestContextHolder.setRequestAttributes(request);
        }
    }
}
