package com.northstar.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The tenant context is set only inside a scope, cleared afterwards, and never seen by another thread. */
class TenantScopeTests {

    private static final TenantContext TENANT_A = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
    private static final TenantContext TENANT_B = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Role.STAFF);

    @AfterEach
    void nothingLeaksOutOfATest() {
        assertThat(TenantScope.current()).isEmpty();
    }

    @Test
    void contextIsVisibleOnlyInsideTheScope() {
        assertThat(TenantScope.current()).isEmpty();
        assertThat(TenantScope.call(TENANT_A, TenantScope::require)).isEqualTo(TENANT_A);
        assertThat(TenantScope.current()).isEmpty();
    }

    @Test
    void contextIsClearedWhenTheWorkThrows() {
        assertThatThrownBy(() -> TenantScope.run(TENANT_A, () -> {
                    throw new IllegalStateException("boom");
                }))
                .hasMessage("boom");
        assertThat(TenantScope.current()).isEmpty();
    }

    @Test
    void requireWithoutAScopeThrows() {
        assertThatThrownBy(TenantScope::require).isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void nestingTheSameContextIsAllowedAndKeepsIt() {
        TenantScope.run(TENANT_A, () -> {
            TenantScope.run(TENANT_A, () -> assertThat(TenantScope.require()).isEqualTo(TENANT_A));
            assertThat(TenantScope.require()).isEqualTo(TENANT_A);
        });
    }

    @Test
    void nestingADifferentTenantIsRefused() {
        TenantScope.run(TENANT_A, () -> {
            assertThatThrownBy(() -> TenantScope.run(TENANT_B, () -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different tenant context");
            assertThat(TenantScope.require()).isEqualTo(TENANT_A);
        });
    }

    @Test
    void anotherPlatformThreadDoesNotSeeTheContext() throws Exception {
        AtomicReference<Optional<TenantContext>> seen = new AtomicReference<>();
        TenantScope.run(TENANT_A, () -> {
            Thread thread = new Thread(() -> seen.set(TenantScope.current()));
            thread.start();
            join(thread);
        });
        assertThat(seen.get()).isEmpty();
    }

    @Test
    void aVirtualThreadDoesNotSeeTheContext() {
        AtomicReference<Optional<TenantContext>> seen = new AtomicReference<>();
        TenantScope.run(TENANT_A, () -> join(Thread.ofVirtual().start(() -> seen.set(TenantScope.current()))));
        assertThat(seen.get()).isEmpty();
    }

    @Test
    void asyncTasksDoNotSeeTheContext() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Optional<TenantContext> fromFuture = TenantScope.call(
                    TENANT_A,
                    () -> CompletableFuture.supplyAsync(TenantScope::current, executor)
                            .join());
            Optional<TenantContext> fromCommonPool = TenantScope.call(
                    TENANT_A,
                    () -> CompletableFuture.supplyAsync(TenantScope::current).join());

            assertThat(fromFuture).isEmpty();
            assertThat(fromCommonPool).isEmpty();
        }
    }

    @Test
    void aPooledThreadKeepsNothingFromAPreviousTask() throws Exception {
        try (ExecutorService single = Executors.newSingleThreadExecutor()) {
            single.submit(() -> TenantScope.run(TENANT_A, () -> {})).get();
            assertThat(single.submit(TenantScope::current).get()).isEmpty();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
