package com.northstar.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.ActionRequest;
import com.northstar.workflow.FailureClass;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The fake-adapter kit on its own (step 1.11): scripts, counting, time and webhooks. No Spring, no database. */
class FakeKitTests {

    private static final Instant START = Instant.parse("2026-11-11T09:00:00Z");

    private final FakeClock clock = new FakeClock(START);
    private final FakeProvider provider = new FakeProvider(clock, false);

    @Test
    void scriptedCallsPlayInOrderThenSucceed() {
        String key = "k-" + UUID.randomUUID();
        provider.script(
                key,
                FakeCall.serverError(503),
                FakeCall.clientTimeout(),
                FakeCall.rateLimited(Duration.ofSeconds(90)),
                FakeCall.rejected("invalid_recipient"),
                FakeCall.authExpired());

        assertThat(call(key, 1)).isEqualTo(ActionOutcome.retryable("http_503"));
        assertThat(call(key, 2)).isEqualTo(ActionOutcome.retryable("timeout"));
        assertThat(call(key, 3)).isEqualTo(ActionOutcome.retryableAfter("http_429", Duration.ofSeconds(90)));
        assertThat(call(key, 4)).isEqualTo(ActionOutcome.permanent("invalid_recipient"));
        assertThat(((ActionOutcome.Failed) call(key, 5)).failureClass()).isEqualTo(FailureClass.AUTH);
        assertThat(call(key, 6)).isEqualTo(ActionOutcome.succeeded("{\"providerId\": \"fake-6\"}"));

        assertThat(provider.calls(key)).isEqualTo(6);
        assertThat(provider.effects(key))
                .as("only the success performed anything")
                .isOne();
        assertThatThrownBy(() -> FakeCall.serverError(429)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutProviderIdempotencyEveryPerformedCallIsAnEffect() {
        String key = "k-" + UUID.randomUUID();
        provider.script(key, FakeCall.performedButLost());
        call(key, 1);
        call(key, 2);
        assertThat(provider.effects(key)).isEqualTo(2);

        String honoured = "k-" + UUID.randomUUID();
        provider.honourIdempotencyKeyFor(honoured);
        provider.script(honoured, FakeCall.performedButLost());
        call(honoured, 1);
        call(honoured, 2);
        assertThat(provider.effects(honoured)).isOne();

        FakeProvider deduping = new FakeProvider(clock, true);
        deduping.script(key, FakeCall.performedButLost());
        deduping.call(request(key, 1));
        deduping.call(request(key, 2));
        assertThat(deduping.effects(key)).isOne();
    }

    @Test
    void aDelayWaitsOnTheFakeClockNotTheWallClock() throws Exception {
        String key = "k-" + UUID.randomUUID();
        provider.script(key, FakeCall.delayed(Duration.ofHours(3), FakeCall.succeed()));

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<ActionOutcome> outcome = pool.submit(() -> call(key, 1));
            assertThat(clock.awaitWaiting(1, Duration.ofSeconds(5))).isTrue();
            clock.advance(Duration.ofHours(2));
            assertThat(outcome.isDone()).isFalse();
            assertThat(provider.effects(key)).isZero();

            clock.advance(Duration.ofHours(1));
            assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(ActionOutcome.Succeeded.class);
        }
        assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofHours(3)));
        assertThat(provider.effects(key)).isOne();
    }

    @Test
    void aHangReturnsOnlyWhenInterrupted() throws Exception {
        String key = "k-" + UUID.randomUUID();
        provider.script(key, FakeCall.hang());

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<ActionOutcome> outcome = pool.submit(() -> call(key, 1));
            assertThat(provider.awaitInterrupted(key, Duration.ofMillis(200))).isFalse();
            outcome.cancel(true);
            assertThat(provider.awaitInterrupted(key, Duration.ofSeconds(5))).isTrue();
        }
        assertThat(provider.effects(key)).isZero();
    }

    @Test
    void aGateHoldsTheCallUntilReleased() throws Exception {
        String key = "k-" + UUID.randomUUID();
        Gate gate = new Gate();
        provider.script(key, FakeCall.gated(gate));

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<ActionOutcome> outcome = pool.submit(() -> call(key, 1));
            assertThat(gate.awaitArrival(Duration.ofSeconds(5))).isTrue();
            assertThat(provider.effects(key)).isZero();
            gate.release();
            assertThat(outcome.get(5, TimeUnit.SECONDS)).isInstanceOf(ActionOutcome.Succeeded.class);
        }
        assertThat(provider.effects(key)).isOne();
    }

    @Test
    void aThrowingAdapterThrows() {
        String key = "k-" + UUID.randomUUID();
        provider.script(key, FakeCall.throwing());
        assertThatThrownBy(() -> call(key, 1)).isInstanceOf(IllegalStateException.class);
        assertThat(provider.effects(key)).isZero();
    }

    @Test
    void signedDeliveriesVerifyAgainstTheirExactBytes() {
        HmacSigner signer = new HmacSigner("X-Test-Signature", "sha256=");
        WebhookScript webhooks = new WebhookScript(signer, clock);
        String body = "{\"id\": \"evt_1\", \"amount\": 50000}";

        WebhookDelivery delivery = webhooks.deliver(body);
        String header = delivery.headers().get("X-Test-Signature");
        assertThat(header).startsWith("sha256=");
        assertThat(header).isEqualTo("sha256=" + independentHmac(signer.secret(), delivery.body()));
        assertThat(header)
                .as("one changed byte breaks it")
                .isNotEqualTo("sha256="
                        + independentHmac(
                                signer.secret(), body.replace("50000", "50001").getBytes()));

        WebhookDelivery forged = webhooks.withInvalidSignature(body);
        assertThat(forged.headers().get("X-Test-Signature"))
                .startsWith("sha256=")
                .isNotEqualTo("sha256=" + independentHmac(signer.secret(), forged.body()));
        assertThat(webhooks.unsigned(body).headers()).doesNotContainKey("X-Test-Signature");
        assertThat(new HmacSigner("X-Test-Signature", "").secret()).isNotEqualTo(signer.secret());
    }

    @Test
    void deliveriesRepeatReorderAndArriveLate() {
        WebhookScript webhooks = new WebhookScript(new HmacSigner("X-Test-Signature", ""), clock);

        List<WebhookDelivery> twice = webhooks.twice("{\"id\": \"evt_2\"}", Duration.ofMinutes(1));
        assertThat(twice.get(1).body()).isEqualTo(twice.get(0).body());
        assertThat(twice.get(1).headers()).isEqualTo(twice.get(0).headers());
        assertThat(twice.get(1).deliveredAt()).isEqualTo(START.plus(Duration.ofMinutes(1)));
        assertThat(twice.get(1).occurredAt()).isEqualTo(twice.get(0).occurredAt());

        List<WebhookDelivery> reordered = webhooks.outOfOrder("{\"seq\": 1}", "{\"seq\": 2}");
        assertThat(reordered).extracting(WebhookDelivery::bodyText).containsExactly("{\"seq\": 2}", "{\"seq\": 1}");
        assertThat(reordered.get(1).occurredAt()).isBefore(reordered.get(0).occurredAt());
        assertThat(reordered.get(1).deliveredAt()).isAfter(reordered.get(0).deliveredAt());

        WebhookDelivery late = webhooks.late("{\"id\": \"evt_3\"}", Duration.ofHours(4));
        assertThat(Duration.between(late.occurredAt(), late.deliveredAt())).isEqualTo(Duration.ofHours(4));
        assertThat(late.withHeader("X-Event-Id", "evt_3").headers())
                .containsEntry("X-Event-Id", "evt_3")
                .containsKey("X-Test-Signature");
    }

    private ActionOutcome call(String key, int attempt) {
        return provider.call(request(key, attempt));
    }

    private static ActionRequest request(String key, int attempt) {
        return new ActionRequest(UUID.randomUUID(), UUID.randomUUID(), "test.fake", key, "{}", attempt);
    }

    /** HMAC-SHA256 computed here, separately from the signer, so the test checks the signer rather than itself. */
    private static String independentHmac(String secret, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
