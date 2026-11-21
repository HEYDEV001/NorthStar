package com.northstar.integrations.internal;

import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Reads one stored account-level change, routed by WABA ID (step 2.8a): the slice {@link WhatsAppDelivery} built, with
 * the envelope, the entry's {@code id} (the WABA ID) and {@code time}, and one change. The slice must agree with its
 * row's key, {@code <field>:<waba id>:<time>:<digest>}; a slice that does not is unusable ({@value #PAYLOAD_INVALID}).
 *
 * <p>Only {@code business_capability_update} is stored, and only its {@code max_daily_conversations_per_business}
 * (the business portfolio's messaging limit) is read; the deprecated {@code max_daily_conversation_per_phone} and the
 * phone-number caps are not (integration-specs.md section 2, Quality and limits).
 */
final class WhatsAppAccountUpdate {

    static final String BUSINESS_CAPABILITY_PREFIX = WhatsAppDelivery.BUSINESS_CAPABILITY_FIELD + ":";

    static final String PAYLOAD_INVALID = "payload_invalid";

    static final String LIMIT_FIELD = "max_daily_conversations_per_business";

    private WhatsAppAccountUpdate() {}

    /** Whether a row's key is an account-level change. */
    static boolean handles(String providerEventId) {
        return providerEventId.startsWith(BUSINESS_CAPABILITY_PREFIX);
    }

    sealed interface Parsed {}

    /**
     * A usable change: its WABA ID, whether it carries the limit field at all, and the limit if it is a value Meta
     * documents (present but empty: a value Northstar does not recognise, never logged).
     */
    record Usable(String wabaId, boolean limitPresent, Optional<MessagingLimit> limit) implements Parsed {

        @Override
        public String toString() {
            return "Usable[limitPresent=" + limitPresent + ", limit=" + limit + "]";
        }
    }

    record Rejected(String errorCode) implements Parsed {}

    static Parsed parse(String providerEventId, String payloadJson) {
        JsonNode root;
        try {
            root = ProviderJson.MAPPER.readTree(payloadJson == null ? "" : payloadJson);
        } catch (JacksonException e) {
            return new Rejected(PAYLOAD_INVALID);
        }
        if (root == null || !root.isObject()) return new Rejected(PAYLOAD_INVALID);
        JsonNode entry = root.path("entry").path(0);
        JsonNode change = entry.path("changes").path(0);
        String wabaId = text(entry.get("id"));
        JsonNode time = entry.get("time");
        String field = text(change.get("field"));
        JsonNode value = change.get("value");
        if (wabaId == null
                || time == null
                || !time.isIntegralNumber()
                || field == null
                || value == null
                || !value.isObject()) {
            return new Rejected(PAYLOAD_INVALID);
        }
        // The slice must be the one its key was made from.
        if (!providerEventId.startsWith(field + ":" + wabaId + ":" + time.asLong() + ":")) {
            return new Rejected(PAYLOAD_INVALID);
        }
        if (!WhatsAppDelivery.BUSINESS_CAPABILITY_FIELD.equals(field)) return new Rejected(PAYLOAD_INVALID);

        JsonNode limit = value.get(LIMIT_FIELD);
        if (limit == null || limit.isNull()) return new Usable(wabaId, false, Optional.empty());
        return new Usable(
                wabaId, true, limit.isString() ? MessagingLimit.fromMeta(limit.stringValue()) : Optional.empty());
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
