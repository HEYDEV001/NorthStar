package com.northstar.integrations.internal;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Reads one stored {@code business_capability_update}, routed by WABA ID (step 2.8a): an {@link AccountChangeSlice}
 * that does not agree with its row's key is unusable ({@value #PAYLOAD_INVALID}).
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

    /** Whether a row's key is a messaging-limit change. */
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
        Optional<AccountChangeSlice.Slice> read = AccountChangeSlice.read(providerEventId, payloadJson);
        if (read.isEmpty()) return new Rejected(PAYLOAD_INVALID);
        AccountChangeSlice.Slice slice = read.get();
        if (!WhatsAppDelivery.BUSINESS_CAPABILITY_FIELD.equals(slice.field())) return new Rejected(PAYLOAD_INVALID);

        JsonNode limit = slice.value().get(LIMIT_FIELD);
        if (limit == null || limit.isNull()) return new Usable(slice.wabaId(), false, Optional.empty());
        return new Usable(
                slice.wabaId(),
                true,
                limit.isString() ? MessagingLimit.fromMeta(limit.stringValue()) : Optional.empty());
    }
}
