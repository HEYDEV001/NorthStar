package com.northstar.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a unit of work runs as: the tenant, the user and their role. It comes only from the verified token and the
 * membership lookup (or, for workers, the job's tenant; for a webhook, the routing lookup), never from anything the
 * client sends. Every transaction sets it as app.tenant_id, app.user_id and app.role (data-model.md section 12).
 */
public record TenantContext(UUID tenantId, UUID userId, Role role) {

    /**
     * The app.user_id of the actors that are not people: the system (workers) and the webhook actor. No profile or
     * membership may have it (database check constraints).
     */
    public static final UUID SYSTEM_USER_ID = new UUID(0, 0);

    /** Only background jobs act as the system; see {@link #system}. */
    private static final String JOBS_PACKAGE = "com.northstar.jobs";

    /** The one class that creates webhook contexts, for tenants the routing lookup returned (step 2.2). */
    private static final String WEBHOOK_ROUTING_CLASS = "com.northstar.tenancy.internal.WebhookRoutingService";

    private static final StackWalker STACK = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        boolean actorWithoutUser = role == Role.SYSTEM || role == Role.WEBHOOK;
        if (actorWithoutUser != SYSTEM_USER_ID.equals(userId)) {
            throw new IllegalArgumentException("The system and webhook roles, and only they, use the nil user");
        }
        if (role == Role.SYSTEM && !calledFromJobs()) {
            throw new IllegalStateException("Only background jobs may act as the system");
        }
        if (role == Role.WEBHOOK && !calledFromWebhookRouting()) {
            throw new IllegalStateException("Only webhook routing may create a webhook context");
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

    /**
     * True for a webhook context (step 2.2): role WEBHOOK with {@link #SYSTEM_USER_ID}, created only by the routing
     * lookup ({@link WebhookRouting#whatsAppTenants}) for a tenant it returned. The tenant guard lets a transaction in
     * this context run exactly one statement, {@link WebhookRouting#INSERT_WEBHOOK_EVENT}.
     */
    public boolean isWebhook() {
        return role == Role.WEBHOOK;
    }

    /** False for the system and webhook actors, which are not people and have no profile or membership. */
    public boolean hasUser() {
        return !SYSTEM_USER_ID.equals(userId);
    }

    /** True when the first caller outside this record is in the jobs package. */
    private static boolean calledFromJobs() {
        return firstCaller()
                .map(type -> type.getPackageName().equals(JOBS_PACKAGE)
                        || type.getPackageName().startsWith(JOBS_PACKAGE + "."))
                .orElse(false);
    }

    /** True when the first caller outside this record is the webhook routing service itself. */
    private static boolean calledFromWebhookRouting() {
        return firstCaller()
                .map(type -> type.getName().equals(WEBHOOK_ROUTING_CLASS))
                .orElse(false);
    }

    /** The class of the first frame outside this record (hidden lambda frames are skipped by the walker). */
    private static Optional<Class<?>> firstCaller() {
        return STACK.<Optional<Class<?>>>walk(frames -> frames.<Class<?>>map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != TenantContext.class)
                .findFirst());
    }
}
