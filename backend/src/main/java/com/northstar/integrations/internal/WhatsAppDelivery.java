package com.northstar.integrations.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Splits a verified WhatsApp Cloud API delivery into items (step 2.2, integration-specs.md section 2). Meta batches up
 * to 1000 updates per delivery and "batching cannot be guaranteed"
 * (developers.facebook.com/docs/graph-api/webhooks/getting-started), so a retry may group items differently. Each item,
 * one element of a change's {@code messages} or {@code statuses}, is therefore stored on its own, with its own dedupe key:
 *
 * <ul>
 *   <li>a message: {@code message:<wamid>}, from {@code messages[].id};
 *   <li>a status: {@code status:<wamid>:<status>}, from {@code statuses[].id} (the sent message's wamid) and
 *       {@code statuses[].status} (sent, delivered, read, failed).
 * </ul>
 *
 * Each item is routed by its change's {@code value.metadata.phone_number_id}. Its payload is a Meta-shaped slice: the
 * envelope, its entry's ID, one change with that change's {@code value} fields (metadata, contacts, messaging_product)
 * and only this item. A batch spanning several numbers, and so several tenants, never puts one number's data in
 * another's slice.
 *
 * <p>Not stored, only counted as ignored: changes whose field is not {@code messages} (template, quality and account
 * updates are keyed by the WABA ID, which step 2.8 routes), value-level {@code errors} (no item ID), items with no
 * usable ID, and changes with no usable phone number ID. A body that is not JSON, or not a
 * {@code whatsapp_business_account} object, is unreadable.
 *
 * <p>Postgres jsonb cannot hold U+0000, and Java strings can carry lone surrogates; both are replaced with U+FFFD in
 * the slice (keys and values), so a message containing them is still stored rather than failing on every retry.
 */
final class WhatsAppDelivery {

    static final String OBJECT = "whatsapp_business_account";
    static final String MESSAGES_FIELD = "messages";

    private static final Pattern ITEM_ID = Pattern.compile("[\\x21-\\x7e]{1,160}");
    private static final Pattern STATUS = Pattern.compile("[a-z_]{1,32}");
    private static final Pattern PHONE_NUMBER_ID = Pattern.compile("[\\x21-\\x7e]{1,64}");
    private static final Set<String> ITEM_ARRAYS = Set.of("messages", "statuses", "errors");
    private static final JsonNodeFactory NODES = ProviderJson.MAPPER.getNodeFactory();

    /** One stored unit: its routing key, dedupe key and payload slice (JSON text and its UTF-8 size). */
    record Item(String phoneNumberId, String providerEventId, String payloadJson, int payloadBytes) {}

    /** A parsed delivery: readable or not, its items in delivery order, and how many parts were ignored. */
    record Parsed(boolean readable, List<Item> items, int ignored) {

        static Parsed unreadable() {
            return new Parsed(false, List.of(), 0);
        }
    }

    private WhatsAppDelivery() {}

    static Parsed parse(byte[] body) {
        JsonNode root;
        try {
            root = ProviderJson.MAPPER.readTree(body);
        } catch (JacksonException e) {
            return Parsed.unreadable();
        }
        if (root == null || !root.isObject() || !OBJECT.equals(text(root.get("object")))) return Parsed.unreadable();

        List<Item> items = new ArrayList<>();
        int ignored = 0;
        for (JsonNode entry : arrayOf(root.get("entry"))) {
            if (!entry.isObject()) {
                ignored++;
                continue;
            }
            for (JsonNode change : arrayOf(entry.get("changes"))) {
                ignored += split(entry, change, items);
            }
        }
        return new Parsed(true, List.copyOf(items), ignored);
    }

    /** Adds the change's items; returns how many parts of it were ignored. */
    private static int split(JsonNode entry, JsonNode change, List<Item> items) {
        JsonNode value = change.isObject() ? change.get("value") : null;
        if (!MESSAGES_FIELD.equals(text(change.get("field"))) || value == null || !value.isObject()) return 1;

        List<JsonNode> messages = arrayOf(value.get("messages"));
        List<JsonNode> statuses = arrayOf(value.get("statuses"));
        int ignored = arrayOf(value.get("errors")).size();
        String phoneNumberId = text(value.path("metadata").get("phone_number_id"));
        if (phoneNumberId == null || !PHONE_NUMBER_ID.matcher(phoneNumberId).matches()) {
            return Math.max(1, ignored + messages.size() + statuses.size());
        }
        for (JsonNode message : messages) {
            String id = message.isObject() ? text(message.get("id")) : null;
            if (id == null || !ITEM_ID.matcher(id).matches()) {
                ignored++;
                continue;
            }
            items.add(item(phoneNumberId, "message:" + id, entry, value, "messages", message));
        }
        for (JsonNode status : statuses) {
            String id = status.isObject() ? text(status.get("id")) : null;
            String state = status.isObject() ? text(status.get("status")) : null;
            if (id == null
                    || !ITEM_ID.matcher(id).matches()
                    || state == null
                    || !STATUS.matcher(state).matches()) {
                ignored++;
                continue;
            }
            items.add(item(phoneNumberId, "status:" + id + ":" + state, entry, value, "statuses", status));
        }
        if (messages.isEmpty() && statuses.isEmpty() && ignored == 0) ignored = 1; // an empty change
        return ignored;
    }

    private static Item item(
            String phoneNumberId, String providerEventId, JsonNode entry, JsonNode value, String kind, JsonNode one) {
        ObjectNode valueSlice = NODES.objectNode();
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            if (!ITEM_ARRAYS.contains(field.getKey()))
                valueSlice.set(clean(field.getKey()), sanitized(field.getValue()));
        }
        valueSlice.putArray(kind).add(sanitized(one));

        ObjectNode changeSlice = NODES.objectNode();
        changeSlice.put("field", MESSAGES_FIELD);
        changeSlice.set("value", valueSlice);

        ObjectNode entrySlice = NODES.objectNode();
        if (entry.get("id") != null) entrySlice.set("id", sanitized(entry.get("id")));
        entrySlice.putArray("changes").add(changeSlice);

        ObjectNode slice = NODES.objectNode();
        slice.put("object", OBJECT);
        slice.putArray("entry").add(entrySlice);

        String json = ProviderJson.MAPPER.writeValueAsString(slice);
        return new Item(phoneNumberId, providerEventId, json, json.getBytes(StandardCharsets.UTF_8).length);
    }

    /** A copy with every string, keys included, safe for jsonb. */
    private static JsonNode sanitized(JsonNode node) {
        if (node.isString()) return NODES.stringNode(clean(node.stringValue()));
        if (node.isArray()) {
            ArrayNode copy = NODES.arrayNode(node.size());
            for (JsonNode element : node.values()) copy.add(sanitized(element));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = NODES.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(clean(field.getKey()), sanitized(field.getValue()));
            }
            return copy;
        }
        return node;
    }

    /** U+0000 and unpaired surrogates become U+FFFD; everything else is kept as sent. */
    static String clean(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean bad;
            if (c == 0) {
                bad = true;
            } else if (Character.isHighSurrogate(c)) {
                bad = i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1));
                if (!bad) {
                    if (out != null) out.append(c).append(text.charAt(i + 1));
                    i++;
                    continue;
                }
            } else {
                bad = Character.isLowSurrogate(c);
            }
            if (bad && out == null) out = new StringBuilder(text.length()).append(text, 0, i);
            if (out != null) out.append(bad ? '�' : c);
        }
        return out == null ? text : out.toString();
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static List<JsonNode> arrayOf(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return List.copyOf(node.values());
    }
}
