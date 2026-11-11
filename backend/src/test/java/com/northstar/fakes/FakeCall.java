package com.northstar.fakes;

import com.northstar.workflow.ActionOutcome;
import java.time.Duration;
import java.util.Objects;

/**
 * One scripted call to a fake provider. Each is what a real adapter would report after classifying the provider's
 * answer (integration-specs.md section 1): 5xx, client timeouts and 429 are retryable, rejections are permanent,
 * expired credentials are auth.
 */
public sealed interface FakeCall {

    /** The side effect happens; the adapter reports success with this result (null: the provider's default). */
    record Succeed(String resultJson) implements FakeCall {}

    /** No side effect; the adapter reports this failure. */
    record Fail(ActionOutcome.Failed failure) implements FakeCall {}

    /** Waits this long on the fake clock, then does the next call. */
    record Delayed(Duration delay, FakeCall then) implements FakeCall {
        public Delayed {
            Objects.requireNonNull(then, "then");
        }
    }

    /** Never answers: blocks until the executor's timeout interrupts it. */
    record Hang() implements FakeCall {}

    /** The side effect happens, but the response is lost: the adapter sees its own client timeout. */
    record PerformedButLost() implements FakeCall {}

    /** Waits at the gate until the test releases it, then does the next call. */
    record Gated(Gate gate, FakeCall then) implements FakeCall {}

    /** The adapter throws something unexpected. */
    record Throwing() implements FakeCall {}

    /** Success with the provider's default result, {"providerId": "fake-<attempt>"}. */
    static FakeCall succeed() {
        return new Succeed(null);
    }

    static FakeCall succeed(String resultJson) {
        return new Succeed(resultJson);
    }

    static FakeCall serverError(int status) {
        if (status < 500 || status > 599) throw new IllegalArgumentException("A server error is 5xx");
        return new Fail((ActionOutcome.Failed) ActionOutcome.retryable("http_" + status));
    }

    /** The adapter's own HTTP client gave up waiting. */
    static FakeCall clientTimeout() {
        return new Fail((ActionOutcome.Failed) ActionOutcome.retryable("timeout"));
    }

    static FakeCall rateLimited(Duration retryAfter) {
        return new Fail((ActionOutcome.Failed) ActionOutcome.retryableAfter("http_429", retryAfter));
    }

    /** Invalid input or revoked authorisation: permanent. */
    static FakeCall rejected(String code) {
        return new Fail((ActionOutcome.Failed) ActionOutcome.permanent(code));
    }

    /** Expired credentials: the action pauses until the integration reconnects. */
    static FakeCall authExpired() {
        return new Fail((ActionOutcome.Failed) ActionOutcome.auth("token_expired"));
    }

    static FakeCall delayed(Duration delay, FakeCall then) {
        return new Delayed(delay, then);
    }

    static FakeCall hang() {
        return new Hang();
    }

    static FakeCall performedButLost() {
        return new PerformedButLost();
    }

    static FakeCall gated(Gate gate) {
        return new Gated(gate, succeed());
    }

    static FakeCall throwing() {
        return new Throwing();
    }
}
