package com.northstar.workflow.internal;

import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionHandler;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.RetryPolicy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Test-only provider stand-in (the full fake-adapter kit is step 1.11). Each idempotency key gets a script of outcomes,
 * then succeeds; every call is counted per key, so a test can show a side effect happened once.
 */
@Component
class ScriptedActionHandler implements ActionHandler {

    static final String TYPE = "test.scripted";

    private final Map<String, Deque<ActionOutcome>> scripts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, Duration> delays = new ConcurrentHashMap<>();
    private final Map<String, UUID> tenantSeen = new ConcurrentHashMap<>();

    void script(String key, ActionOutcome... outcomes) {
        scripts.put(key, new ArrayDeque<>(List.of(outcomes)));
    }

    void delay(String key, Duration delay) {
        delays.put(key, delay);
    }

    int calls(String key) {
        AtomicInteger count = calls.get(key);
        return count == null ? 0 : count.get();
    }

    UUID tenantSeen(String key) {
        return tenantSeen.get(key);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.STANDARD_BACKOFF;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(5);
    }

    @Override
    public ActionOutcome execute(ActionRequest request) {
        String key = request.idempotencyKey();
        calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        tenantSeen.put(key, TenantScope.require().tenantId());
        Duration delay = delays.get(key);
        if (delay != null) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Deque<ActionOutcome> script = scripts.get(key);
        ActionOutcome next = script == null ? null : script.poll();
        return next != null ? next : ActionOutcome.succeeded("{\"providerId\": \"fake-" + request.attempt() + "\"}");
    }
}
