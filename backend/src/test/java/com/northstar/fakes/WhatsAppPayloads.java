package com.northstar.fakes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds WhatsApp Cloud API webhook bodies shaped as Meta sends them (step 2.2;
 * developers.facebook.com/docs/whatsapp/cloud-api/webhooks/components): {@code object}, then one entry per call, each
 * with its changes. Every value is fake: phone number IDs and wamids are random, customer numbers are in a range no
 * real subscriber has, and message text is whatever the test passes (use {@link #marker()} to look for it in logs).
 */
public final class WhatsAppPayloads {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectNode root = JSON.createObjectNode();
    private final ArrayNode entries;

    private WhatsAppPayloads() {
        root.put("object", "whatsapp_business_account");
        entries = root.putArray("entry");
    }

    public static WhatsAppPayloads delivery() {
        return new WhatsAppPayloads();
    }

    /** A random, wamid-shaped message ID. */
    public static String wamid() {
        return "wamid.TEST" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /** A random phone number ID (Meta's are digits; this one is clearly a test value). */
    public static String phoneNumberId() {
        return "pn-" + UUID.randomUUID();
    }

    /** Text no log line should ever contain. */
    public static String marker() {
        return "body-marker-" + UUID.randomUUID();
    }

    /** An entry with one messages change holding these text messages, for this business number. */
    public WhatsAppPayloads messages(String phoneNumberId, List<String> wamids, String text) {
        ObjectNode value = messagesValue(phoneNumberId);
        value.putArray("contacts")
                .addObject()
                .put("wa_id", "910000000001")
                .putObject("profile")
                .put("name", "Test Customer");
        ArrayNode messages = value.putArray("messages");
        for (String wamid : wamids) {
            ObjectNode message = messages.addObject()
                    .put("from", "910000000001")
                    .put("id", wamid)
                    .put("timestamp", "1763550000")
                    .put("type", "text");
            message.putObject("text").put("body", text);
        }
        return change("messages", value);
    }

    public WhatsAppPayloads message(String phoneNumberId, String wamid, String text) {
        return messages(phoneNumberId, List.of(wamid), text);
    }

    /** An entry with one messages change holding a status update for a message this business sent. */
    public WhatsAppPayloads status(String phoneNumberId, String wamid, String status) {
        ObjectNode value = messagesValue(phoneNumberId);
        value.putArray("statuses")
                .addObject()
                .put("id", wamid)
                .put("status", status)
                .put("timestamp", "1763550001")
                .put("recipient_id", "910000000001");
        return change("messages", value);
    }

    /** An entry with one change of another field (for example a template status update), keyed by the WABA only. */
    public WhatsAppPayloads otherField(String field) {
        ObjectNode value = JSON.createObjectNode().put("event", "APPROVED").put("message_template_id", 1234567890L);
        return change(field, value);
    }

    /** An entry whose messages change carries only value-level errors (no item ID). */
    public WhatsAppPayloads errorsOnly(String phoneNumberId) {
        ObjectNode value = messagesValue(phoneNumberId);
        value.putArray("errors").addObject().put("code", 131000).put("title", "Something went wrong");
        return change("messages", value);
    }

    private ObjectNode messagesValue(String phoneNumberId) {
        ObjectNode value = JSON.createObjectNode().put("messaging_product", "whatsapp");
        value.putObject("metadata").put("display_phone_number", "15550000000").put("phone_number_id", phoneNumberId);
        return value;
    }

    private WhatsAppPayloads change(String field, ObjectNode value) {
        ObjectNode entry = entries.addObject().put("id", "waba-test-" + entries.size());
        ObjectNode change = entry.putArray("changes").addObject();
        change.put("field", field);
        change.set("value", value);
        return this;
    }

    /** Several changes in one entry, as Meta may batch them. */
    public WhatsAppPayloads inOneEntry() {
        if (entries.size() < 2) return this;
        List<ObjectNode> changes = new ArrayList<>();
        for (var entry : entries.values()) {
            for (var change : entry.get("changes").values()) changes.add((ObjectNode) change);
        }
        entries.removeAll();
        ArrayNode merged = entries.addObject().put("id", "waba-test-merged").putArray("changes");
        changes.forEach(merged::add);
        return this;
    }

    public String json() {
        return JSON.writeValueAsString(root);
    }
}
