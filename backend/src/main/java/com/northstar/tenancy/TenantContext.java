package com.northstar.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a unit of work runs as: the tenant, the user and their role. It comes only from the verified token and the
 * membership lookup (or, for workers, the job's tenant), never from anything the client sends. Every transaction sets
 * it as app.tenant_id, app.user_id and app.role (data-model.md section 12).
 */
public record TenantContext(UUID tenantId, UUID userId, Role role) {

    /** The system actor's app.user_id. No profile or membership may have it (database check constraints). */
    public static final UUID SYSTEM_USER_ID = new UUID(0, 0);

    /** Only background jobs act as the system; see {@link #system}. */
    private static final String JOBS_PACKAGE = "com.northstar.jobs";

    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        if ((role == Role.SYSTEM) != SYSTEM_USER_ID.equals(userId)) {
            throw new IllegalArgumentException("The system user and the system role go together");
        }
        if (role == Role.SYSTEM && !calledFromJobs()) {
            throw new IllegalStateException("Only background jobs may act as the system");
        }
    }

    /**
     * The worker's context for a job's tenant (step 1.10): role SYSTEM with {@link #SYSTEM_USER_ID}. Callable only from
     * {@code com.northstar.jobs}, and {@link TenantScope} refuses it on a thread serving a web request, so no HTTP path
     * can run as the system.
     */
    public static TenantContext system(UUID tenantId) {
        return new TenantContext(tenantId, SYSTEM_USER_ID, Role.SYSTEM);
    }

    public boolean isSystem() {
        return role == Role.SYSTEM;
    }

    /** True when the first caller outside this record is in the jobs package. */
    private static boolean calledFromJobs() {
        return STACK.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != TenantContext.class)
                .findFirst()
                .map(type -> type.getPackageName().equals(JOBS_PACKAGE)
                        || type.getPackageName().startsWith(JOBS_PACKAGE + "."))
                .orElse(false));
    }
}
