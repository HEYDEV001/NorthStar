package com.northstar.contacts;

import java.time.Instant;
import java.util.Objects;

/**
 * An inbound WhatsApp message to store: its provider ID (the wamid), type, body (null where the type has none) and
 * WhatsApp's timestamp. The body is message text: it is stored in {@code messages.body} only and never logged.
 */
public record InboundMessage(String providerMessageId, InboundMessageType type, String body, Instant sentAt) {

    public InboundMessage {
        Objects.requireNonNull(providerMessageId, "providerMessageId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sentAt, "sentAt");
    }

    /** The body is message text, so it never appears in logs or exception messages. */
    @Override
    public String toString() {
        return "InboundMessage[type=" + type.code() + "]";
    }
}
