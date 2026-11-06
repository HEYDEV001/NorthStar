package com.northstar.tenancy;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Holds the {@link TenantContext} for the current thread while a unit of work runs. The only way to set it is
 * {@link #run} or {@link #call}, which always clear it afterwards, even when the work throws.
 *
 * <p>The context is deliberately not inherited: a new thread, virtual thread, async method or CompletableFuture starts
 * without one and fails closed. Code that needs the context elsewhere must pass it explicitly and open its own scope.
 */
public final class TenantScope {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantScope() {}

    public static void run(TenantContext context, Runnable work) {
        call(context, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs the work with the given context. Nesting is allowed only for the same context: an open transaction is
     * already bound to the outer tenant, so switching tenants inside it would be unsafe.
     */
    public static <T> T call(TenantContext context, Supplier<T> work) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        TenantContext outer = CURRENT.get();
        if (outer != null) {
            if (!outer.equals(context)) {
                throw new IllegalStateException("A different tenant context is already active on this thread");
            }
            return work.get();
        }
        CURRENT.set(context);
        try {
            return work.get();
        } finally {
            CURRENT.remove();
        }
    }

    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantContext require() {
        TenantContext context = CURRENT.get();
        if (context == null) throw new MissingTenantContextException("No tenant context on this thread");
        return context;
    }
}
