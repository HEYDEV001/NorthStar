package com.northstar.fakes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Builds webhook deliveries the way providers really send them: once, twice, out of order, late, badly signed or
 * unsigned (P1, P15; W18, W20 later). Bodies are given as text and signed as UTF-8 bytes. Times come from the fake
 * clock; the clock is not moved.
 */
public final class WebhookScript {

    private final HmacSigner signer;
    private final FakeClock clock;

    public WebhookScript(HmacSigner signer, FakeClock clock) {
        this.signer = signer;
        this.clock = clock;
    }

    /** One correctly signed delivery, happening and delivered now. */
    public WebhookDelivery deliver(String body) {
        Instant now = clock.instant();
        return signed(body, now, now);
    }

    /** The same delivery twice, byte for byte, the second one gap later (a provider retry). */
    public List<WebhookDelivery> twice(String body, Duration gap) {
        Instant now = clock.instant();
        return List.of(signed(body, now, now), signed(body, now, now.plus(gap)));
    }

    /** Two events delivered in the opposite order to the one they happened in. */
    public List<WebhookDelivery> outOfOrder(String first, String second) {
        Instant now = clock.instant();
        Instant later = now.plusSeconds(1);
        return List.of(signed(second, later, later), signed(first, now, later.plusMillis(1)));
    }

    /** An event delivered this long after it happened. */
    public WebhookDelivery late(String body, Duration lateness) {
        Instant now = clock.instant();
        return signed(body, now, now.plus(lateness));
    }

    /** Well formed, but signed with another secret: verification must fail. */
    public WebhookDelivery withInvalidSignature(String body) {
        HmacSigner impostor = new HmacSigner(signer.headerName(), signer.prefix());
        Instant now = clock.instant();
        byte[] bytes = bytes(body);
        return new WebhookDelivery(bytes, Map.of(signer.headerName(), impostor.sign(bytes)), now, now);
    }

    /** No signature header at all. */
    public WebhookDelivery unsigned(String body) {
        Instant now = clock.instant();
        return new WebhookDelivery(bytes(body), Map.of(), now, now);
    }

    private WebhookDelivery signed(String body, Instant occurredAt, Instant deliveredAt) {
        byte[] bytes = bytes(body);
        return new WebhookDelivery(bytes, Map.of(signer.headerName(), signer.sign(bytes)), occurredAt, deliveredAt);
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
