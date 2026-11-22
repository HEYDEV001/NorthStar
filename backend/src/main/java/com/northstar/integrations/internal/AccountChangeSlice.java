package com.northstar.integrations.internal;

import java.time.Instant;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * One stored account-level change, routed by WABA ID (steps 2.8a and 2.8b): the slice {@link WhatsAppDelivery} built,
 * with the envelope, the entry's {@code id} (the WABA ID) and {@code time}, and one change. A slice is usable only if
 * it agrees with its row's key, {@code <field>:<waba id>:<time>:<digest>}.
 */
final class AccountChangeSlice {

    /** A usable slice: its field, WABA ID, Meta's time for it, and the change's value. */
    record Slice(String field, String wabaId, Instant time, JsonNode value) {

        @Override
        public String toString() {
            return "Slice[field=" + field + "]";
        }
    }

    private AccountChangeSlice() {}

    /** The slice, if the payload is one and matches its key; empty otherwise. */
    static Optional<Slice> read(String providerEventId, String payloadJson) {
        JsonNode root;
        try {
            root = ProviderJson.MAPPER.readTree(payloadJson == null ? "" : payloadJson);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) return Optional.empty();
        JsonNode entry = root.path("entry").path(0);
        JsonNode change = entry.path("changes").path(0);
        String wabaId = text(entry.get("id"));
        JsonNode time = entry.get("time");
        String field = text(change.get("field"));
        JsonNode value = change.get("value");
        if (wabaId == null
                || time == null
                || !time.isIntegralNumber()
                || !time.canConvertToLong()
                || field == null
                || value == null
                || !value.isObject()) {
            return Optional.empty();
        }
        // The slice must be the one its key was made from.
        if (!providerEventId.startsWith(field + ":" + wabaId + ":" + time.asLong() + ":")) return Optional.empty();
        return Optional.of(new Slice(field, wabaId, Instant.ofEpochSecond(time.asLong()), value));
    }

    static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
