package com.northstar.fakes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One webhook delivery as it reaches the endpoint: the raw body bytes (signatures cover these exact bytes), the headers,
 * when the event happened at the provider and when it was delivered (both on the fake clock).
 */
public record WebhookDelivery(byte[] body, Map<String, String> headers, Instant occurredAt, Instant deliveredAt) {

    public WebhookDelivery {
        body = body.clone();
        headers = Map.copyOf(headers);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** A copy with one more header, e.g. a provider's event ID header. */
    public WebhookDelivery withHeader(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new WebhookDelivery(body, more, occurredAt, deliveredAt);
    }
}
