package com.northstar.integrations.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads one stored template webhook change, routed by WABA ID (step 2.8b; Meta's {@code message_template_status_update}
 * and {@code template_category_update} references, checked 22 Nov 2026), from its {@link AccountChangeSlice}:
 *
 * <ul>
 *   <li>a status change: Meta's template ID ({@code message_template_id}, an integer), its {@code event} and its
 *       {@code reason};
 *   <li>a category change: Meta's template ID and {@code new_category}, either applied (with
 *       {@code previous_category}) or a notice 24 hours ahead (with {@code correct_category} and
 *       {@code category_update_timestamp}, when {@code new_category} is the current one).
 * </ul>
 *
 * Meta's time for either is the entry's {@code time}. The template's name, language and Meta's other text are not read.
 * A slice that does not agree with its key, or lacks the template ID, is unusable ({@value #PAYLOAD_INVALID}).
 */
final class WhatsAppTemplateUpdate {

    static final String STATUS_FIELD = "message_template_status_update";
    static final String CATEGORY_FIELD = "template_category_update";

    static final String PAYLOAD_INVALID = "payload_invalid";

    /** Meta's template IDs are digits (an integer in webhooks, a string in listings). */
    private static final Pattern TEMPLATE_ID = Pattern.compile("[1-9][0-9]{0,63}");

    private static final Pattern EVENT = Pattern.compile("[A-Z_]{1,40}");

    private WhatsAppTemplateUpdate() {}

    /** Whether a row's key is a template change. */
    static boolean handles(String providerEventId) {
        return providerEventId.startsWith(STATUS_FIELD + ":") || providerEventId.startsWith(CATEGORY_FIELD + ":");
    }

    sealed interface Parsed {}

    /** A status change; event is null when Meta sent none that looks like an event. */
    record StatusChange(String providerTemplateId, String event, String reason, Instant at) implements Parsed {

        @Override
        public String toString() {
            return "StatusChange[event=" + event + "]";
        }
    }

    /**
     * A category change. Applied: the template is now in newCategory. A notice (applied false): it is in newCategory and
     * Meta will move it to correctCategory. Categories are empty when not one Meta documents the shape of.
     */
    record CategoryChange(
            String providerTemplateId,
            boolean applied,
            Optional<String> newCategory,
            Optional<String> correctCategory,
            Instant at)
            implements Parsed {

        @Override
        public String toString() {
            return "CategoryChange[applied=" + applied + "]";
        }
    }

    record Rejected(String errorCode) implements Parsed {}

    static Parsed parse(String providerEventId, String payloadJson) {
        Optional<AccountChangeSlice.Slice> read = AccountChangeSlice.read(providerEventId, payloadJson);
        if (read.isEmpty()) return new Rejected(PAYLOAD_INVALID);
        AccountChangeSlice.Slice slice = read.get();
        Optional<String> templateId = templateId(slice.value().get("message_template_id"));
        if (templateId.isEmpty()) return new Rejected(PAYLOAD_INVALID);
        JsonNode value = slice.value();

        if (STATUS_FIELD.equals(slice.field())) {
            String event = AccountChangeSlice.text(value.get("event"));
            String reason = AccountChangeSlice.text(value.get("reason"));
            return new StatusChange(
                    templateId.get(),
                    event != null && EVENT.matcher(event).matches() ? event : null,
                    reason != null && EVENT.matcher(reason).matches() ? reason : null,
                    slice.time());
        }
        if (CATEGORY_FIELD.equals(slice.field())) {
            boolean notice = value.has("correct_category") || value.has("category_update_timestamp");
            return new CategoryChange(
                    templateId.get(),
                    !notice,
                    TemplateStatuses.category(AccountChangeSlice.text(value.get("new_category"))),
                    TemplateStatuses.category(AccountChangeSlice.text(value.get("correct_category"))),
                    slice.time());
        }
        return new Rejected(PAYLOAD_INVALID);
    }

    /** Meta's template ID as digits, from an integer or a string of digits. */
    static Optional<String> templateId(JsonNode node) {
        if (node == null) return Optional.empty();
        String id;
        if (node.isIntegralNumber()) id = node.bigIntegerValue().toString();
        else if (node.isString()) id = node.stringValue();
        else return Optional.empty();
        return TEMPLATE_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }
}
