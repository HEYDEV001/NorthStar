package com.northstar.integrations.internal;

import com.northstar.contacts.InboundMessage;
import com.northstar.contacts.InboundMessageType;
import com.northstar.contacts.PhoneNumbers;
import java.time.Instant;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * One inbound WhatsApp message, read from the slice step 2.2 stored for it (step 2.4b; integration-specs.md section 2;
 * developers.facebook.com/docs/whatsapp/cloud-api/webhooks/components). The slice holds one change whose
 * {@code value} has the business number's metadata, the sender's {@code contacts} entry and exactly one message.
 *
 * <p>Types (decided 20 Nov 2026): text is stored as {@code text}; {@code interactive} button and list replies and
 * template quick-reply {@code button}s as {@code interactive}, with the reply's title or text as body; image and
 * document as their type with the caption as body; audio and location as their type with no body (coordinates and media
 * IDs stay in the webhook payload, kept 30 days, for step 4.5); everything else, unknown types included, as
 * {@code unsupported} with no body. Reaction and system messages never open a lead on their own.
 *
 * <p>A slice that cannot be used is rejected with a fixed code: {@value #PAYLOAD_INVALID}, or
 * {@value #CONTACT_PHONE_INVALID} when the sender is not an E.164 phone number (P4). Nothing from the slice, and no
 * parser message, ever reaches a log: {@link #toString()} shows the type only.
 *
 * @param wamid the message ID, which must match the row's provider event ID
 * @param phoneNumberId the business number it was sent to
 * @param from the sender, E.164
 * @param profileName the sender's WhatsApp profile name, or null
 * @param sentAt WhatsApp's timestamp of the message
 * @param body null where the type has none
 * @param mayOpenLead false for reactions and system messages
 */
record WhatsAppInboundMessage(
        String wamid,
        String phoneNumberId,
        String from,
        String profileName,
        Instant sentAt,
        InboundMessageType type,
        String body,
        boolean mayOpenLead) {

    static final String PAYLOAD_INVALID = "payload_invalid";
    static final String CONTACT_PHONE_INVALID = "contact_phone_invalid";

    static final String MESSAGE_EVENT_PREFIX = "message:";

    private static final Pattern TYPE = Pattern.compile("[a-z_]{1,32}");
    private static final Pattern SECONDS = Pattern.compile("[0-9]{1,12}");

    /** The last second of the year 9999; later timestamps are not WhatsApp's. */
    private static final long MAX_EPOCH_SECOND = 253_402_300_799L;

    /** A parsed slice: a usable message, or the fixed code it was rejected with. */
    sealed interface Parsed permits Usable, Rejected {}

    record Usable(WhatsAppInboundMessage message) implements Parsed {}

    record Rejected(String errorCode) implements Parsed {}

    static Parsed parse(String providerEventId, String payloadJson) {
        JsonNode root;
        try {
            root = ProviderJson.MAPPER.readTree(payloadJson);
        } catch (JacksonException e) {
            return new Rejected(PAYLOAD_INVALID); // the exception quotes the input, so it goes nowhere
        }
        if (root == null) return new Rejected(PAYLOAD_INVALID);
        JsonNode value = root.path("entry").path(0).path("changes").path(0).path("value");
        JsonNode messages = value.path("messages");
        if (!messages.isArray() || messages.size() != 1) return new Rejected(PAYLOAD_INVALID);
        JsonNode message = messages.path(0);

        String wamid = text(message.path("id"));
        if (wamid == null || !(MESSAGE_EVENT_PREFIX + wamid).equals(providerEventId)) {
            return new Rejected(PAYLOAD_INVALID);
        }
        String phoneNumberId = text(value.path("metadata").path("phone_number_id"));
        Instant sentAt = timestamp(message.path("timestamp"));
        String type = text(message.path("type"));
        if (phoneNumberId == null
                || sentAt == null
                || type == null
                || !TYPE.matcher(type).matches()) {
            return new Rejected(PAYLOAD_INVALID);
        }

        // WhatsApp sends the sender's number as digits without the plus sign (wa_id).
        String rawFrom = text(message.path("from"));
        String from = rawFrom == null ? null : "+" + rawFrom;
        if (!PhoneNumbers.isE164(from)) return new Rejected(CONTACT_PHONE_INVALID);

        InboundMessageType storedType;
        String body = null;
        boolean mayOpenLead = true;
        switch (type) {
            case "text" -> {
                storedType = InboundMessageType.TEXT;
                body = text(message.path("text").path("body"));
            }
            case "interactive" -> {
                String reply = text(message.path("interactive").path("type"));
                if ("button_reply".equals(reply) || "list_reply".equals(reply)) {
                    storedType = InboundMessageType.INTERACTIVE;
                    body = text(message.path("interactive").path(reply).path("title"));
                } else {
                    storedType = InboundMessageType.UNSUPPORTED;
                }
            }
            case "button" -> {
                storedType = InboundMessageType.INTERACTIVE;
                body = text(message.path("button").path("text"));
            }
            case "image" -> {
                storedType = InboundMessageType.IMAGE;
                body = text(message.path("image").path("caption"));
            }
            case "document" -> {
                storedType = InboundMessageType.DOCUMENT;
                body = text(message.path("document").path("caption"));
            }
            case "audio" -> storedType = InboundMessageType.AUDIO;
            case "location" -> storedType = InboundMessageType.LOCATION;
            case "reaction", "system" -> {
                storedType = InboundMessageType.UNSUPPORTED;
                mayOpenLead = false;
            }
            default -> storedType = InboundMessageType.UNSUPPORTED;
        }

        return new Usable(new WhatsAppInboundMessage(
                wamid, phoneNumberId, from, profileName(value, rawFrom), sentAt, storedType, body, mayOpenLead));
    }

    /** The message as the contacts module stores it. */
    InboundMessage toStored() {
        return new InboundMessage(wamid, type, body, sentAt);
    }

    /** The sender's profile name: the {@code contacts} entry whose wa_id is the sender's. */
    private static String profileName(JsonNode value, String rawFrom) {
        for (JsonNode contact : value.path("contacts").values()) {
            if (rawFrom.equals(text(contact.path("wa_id"))))
                return text(contact.path("profile").path("name"));
        }
        return null;
    }

    /** Unix seconds, sent by Meta as a string of digits (an integer is accepted too). */
    private static Instant timestamp(JsonNode node) {
        long seconds;
        if (node.isString() && SECONDS.matcher(node.stringValue()).matches()) {
            seconds = Long.parseLong(node.stringValue());
        } else if (node.isIntegralNumber() && node.canConvertToLong()) {
            seconds = node.longValue();
        } else {
            return null;
        }
        return seconds > 0 && seconds <= MAX_EPOCH_SECOND ? Instant.ofEpochSecond(seconds) : null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    /** The type only: the rest is personal data or message text. */
    @Override
    public String toString() {
        return "WhatsAppInboundMessage[type=" + type.code() + "]";
    }
}
