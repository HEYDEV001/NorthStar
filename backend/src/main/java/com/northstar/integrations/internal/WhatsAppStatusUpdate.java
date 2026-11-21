package com.northstar.integrations.internal;

import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * One delivery status, read from the slice step 2.2 stored for it (step 2.7; integration-specs.md section 2; Meta's
 * status webhook reference): the change's {@code value.statuses} holds exactly that status. Its {@code id} (the sent
 * message's wamid) and {@code status} must match the row's provider event ID, {@code status:<wamid>:<status>}, and its
 * {@code timestamp} must be Meta's Unix seconds; otherwise the slice is rejected as {@value #PAYLOAD_INVALID}.
 *
 * <p>Only {@code errors[0].code} is read from a failure: never Meta's title, message or details, which can echo the
 * request. The status itself is not judged here (a status Northstar does not track is the processor's to close).
 * Nothing from the slice reaches a log: {@link #toString()} shows the status only.
 *
 * @param wamid the sent message's provider ID
 * @param status Meta's status, as sent
 * @param at Meta's timestamp of the status
 * @param metaErrorCode the first error's code, or null when there is none
 */
record WhatsAppStatusUpdate(String wamid, String status, Instant at, Integer metaErrorCode) {

    static final String PAYLOAD_INVALID = WhatsAppInboundMessage.PAYLOAD_INVALID;
    static final String STATUS_EVENT_PREFIX = "status:";

    /** A parsed slice: a usable status, or the fixed code it was rejected with. */
    sealed interface Parsed permits Usable, Rejected {}

    record Usable(WhatsAppStatusUpdate status) implements Parsed {}

    record Rejected(String errorCode) implements Parsed {}

    static Parsed parse(String providerEventId, String payloadJson) {
        JsonNode root;
        try {
            root = ProviderJson.MAPPER.readTree(payloadJson);
        } catch (JacksonException e) {
            return new Rejected(PAYLOAD_INVALID); // the exception quotes the input, so it goes nowhere
        }
        if (root == null) return new Rejected(PAYLOAD_INVALID);
        JsonNode statuses =
                root.path("entry").path(0).path("changes").path(0).path("value").path("statuses");
        if (!statuses.isArray() || statuses.size() != 1) return new Rejected(PAYLOAD_INVALID);
        JsonNode item = statuses.path(0);

        String wamid = text(item.path("id"));
        String status = text(item.path("status"));
        if (wamid == null || status == null || !(STATUS_EVENT_PREFIX + wamid + ":" + status).equals(providerEventId)) {
            return new Rejected(PAYLOAD_INVALID);
        }
        Instant at = WhatsAppInboundMessage.timestamp(item.path("timestamp"));
        if (at == null) return new Rejected(PAYLOAD_INVALID);
        JsonNode code = item.path("errors").path(0).path("code");
        Integer metaErrorCode = code.isIntegralNumber() && code.canConvertToInt() ? code.intValue() : null;
        return new Usable(new WhatsAppStatusUpdate(wamid, status, at, metaErrorCode));
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() && !node.stringValue().isEmpty() ? node.stringValue() : null;
    }

    @Override
    public String toString() {
        return "WhatsAppStatusUpdate[status=" + status + "]";
    }
}
