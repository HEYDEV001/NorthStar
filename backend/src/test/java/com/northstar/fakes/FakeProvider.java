package com.northstar.fakes;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A provider that plays a script of {@link FakeCall}s per idempotency key, then succeeds, and counts what reached it.
 *
 * <p>{@link #calls} counts requests; {@link #effects} counts side effects actually performed (messages sent, links
 * created). By default the provider does not honour idempotency keys, as Meta's Conversions API does not: every
 * performed call is a new effect, so "sent once" is proven by Northstar, not by the fake. A test can switch honouring on
 * per key to model a provider that dedupes.
 */
public final class FakeProvider {

    /** One request as the provider saw it, with the tenant scope it ran in (null if none). */
    public record Call(String idempotencyKey, int attempt, UUID tenantId) {}

    private final FakeClock clock;
    private final boolean honoursIdempotencyKeys;
    private final Map<String, Deque<FakeCall>> scripts = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> effects = new ConcurrentHashMap<>();
    private final Set<String> honouredKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> performedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, CountDownLatch> interrupted = new ConcurrentHashMap<>();

    public FakeProvider(FakeClock clock, boolean honoursIdempotencyKeys) {
        this.clock = clock;
        this.honoursIdempotencyKeys = honoursIdempotencyKeys;
    }

    /** The next calls for this key, in order; after them, calls succeed. */
    public void script(String idempotencyKey, FakeCall... next) {
        scripts.put(idempotencyKey, new ArrayDeque<>(List.of(next)));
    }

    /** From now on, a repeated performed call with this key creates no new effect. */
    public void honourIdempotencyKeyFor(String idempotencyKey) {
        honouredKeys.add(idempotencyKey);
    }

    public int calls(String idempotencyKey) {
        return (int) calls.stream()
                .filter(call -> call.idempotencyKey().equals(idempotencyKey))
                .count();
    }

    public List<Call> callsFor(String idempotencyKey) {
        return calls.stream()
                .filter(call -> call.idempotencyKey().equals(idempotencyKey))
                .toList();
    }

    public int effects(String idempotencyKey) {
        return effects.getOrDefault(idempotencyKey, 0);
    }

    /** True once a call for this key was interrupted while hanging or waiting (waits at most the limit). */
    public boolean awaitInterrupted(String idempotencyKey, Duration limit) throws InterruptedException {
        return latch(idempotencyKey).await(limit.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Plays the next scripted call for the request's idempotency key. */
    public ActionOutcome call(ActionRequest request) {
        return call(request.idempotencyKey(), request.attempt(), () -> {});
    }

    /**
     * Plays the next scripted call for a provider adapter that sees only the key (an email sender, for example).
     * onEffect runs exactly when the provider performs a new side effect, so a fake can record what it "sent".
     */
    public ActionOutcome call(String idempotencyKey, Runnable onEffect) {
        return call(idempotencyKey, calls(idempotencyKey) + 1, onEffect);
    }

    /** The provider forgets this key, as Resend does after 24 hours: the next performed call is a new effect. */
    public void expireIdempotencyKey(String idempotencyKey) {
        performedKeys.remove(idempotencyKey);
    }

    private ActionOutcome call(String key, int attempt, Runnable onEffect) {
        UUID tenant = TenantScope.current().map(TenantContext::tenantId).orElse(null);
        calls.add(new Call(key, attempt, tenant));
        Deque<FakeCall> script = scripts.get(key);
        FakeCall next = script == null ? null : script.poll();
        try {
            return play(key, attempt, onEffect, next != null ? next : FakeCall.succeed());
        } catch (InterruptedException e) {
            latch(key).countDown();
            Thread.currentThread().interrupt();
            return ActionOutcome.retryable("interrupted"); // the executor has already given up on this call
        }
    }

    private ActionOutcome play(String key, int attempt, Runnable onEffect, FakeCall call) throws InterruptedException {
        return switch (call) {
            case FakeCall.Succeed succeed -> {
                perform(key, onEffect);
                String result = succeed.resultJson() != null
                        ? succeed.resultJson()
                        : "{\"providerId\": \"fake-" + attempt + "\"}";
                yield ActionOutcome.succeeded(result);
            }
            case FakeCall.Fail fail -> fail.failure();
            case FakeCall.Delayed delayed -> {
                clock.awaitUntil(clock.instant().plus(delayed.delay()));
                yield play(key, attempt, onEffect, delayed.then());
            }
            case FakeCall.Hang hang -> {
                new CountDownLatch(1).await();
                throw new IllegalStateException("unreachable");
            }
            case FakeCall.PerformedButLost lost -> {
                perform(key, onEffect);
                yield ActionOutcome.retryable("timeout");
            }
            case FakeCall.Gated gated -> {
                gated.gate().pass();
                yield play(key, attempt, onEffect, gated.then());
            }
            case FakeCall.Throwing throwing -> throw new IllegalStateException("Fake provider failure");
        };
    }

    private void perform(String key, Runnable onEffect) {
        boolean honoured = honoursIdempotencyKeys || honouredKeys.contains(key);
        boolean firstTime = performedKeys.add(key);
        if (firstTime || !honoured) {
            effects.merge(key, 1, Integer::sum);
            onEffect.run();
        }
    }

    private CountDownLatch latch(String key) {
        return interrupted.computeIfAbsent(key, k -> new CountDownLatch(1));
    }
}
