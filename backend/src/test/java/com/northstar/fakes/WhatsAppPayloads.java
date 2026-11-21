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

    /** A random WhatsApp Business Account ID (Meta's are digits; this one is clearly a test value). */
    public static String wabaId() {
        return "waba-" + UUID.randomUUID();
    }

    /**
     * An entry for this business account at this Unix time with one {@code business_capability_update} change (step
     * 2.8a), shaped as Meta's webhook reference shows it: the portfolio's messaging limit in
     * {@code max_daily_conversations_per_business} (null leaves it out), and the phone-number cap beside it.
     */
    public WhatsAppPayloads capabilityUpdate(String wabaId, long time, String limit) {
        ObjectNode value = JSON.createObjectNode();
        if (limit != null) value.put("max_daily_conversations_per_business", limit);
        value.put("max_phone_numbers_per_waba", 25);
        return accountChange(wabaId, time, "business_capability_update", value);
    }

    /** An entry for this business account at this Unix time with one change of any field (step 2.8a). */
    public WhatsAppPayloads accountChange(String wabaId, long time, String field, ObjectNode value) {
        ObjectNode entry = entries.addObject().put("id", wabaId).put("time", time);
        ObjectNode change = entry.putArray("changes").addObject();
        change.put("field", field);
        change.set("value", value);
        return this;
    }

    /** An empty object to build a change's value with. */
    public static ObjectNode object() {
        return JSON.createObjectNode();
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

    /**
     * A status update as Meta's status reference describes it (step 2.7): Meta's timestamp, a pricing object, the
     * recipient's number and, when errorCode is not null, one error whose title, message and details echo the
     * recipient's number and {@link #marker()}-style text, so tests can show none of it is logged or stored.
     */
    public WhatsAppPayloads status(
            String phoneNumberId, String wamid, String status, java.time.Instant at, Integer errorCode, String echo) {
        ObjectNode value = messagesValue(phoneNumberId);
        ObjectNode item = value.putArray("statuses")
                .addObject()
                .put("id", wamid)
                .put("status", status)
                .put("timestamp", Long.toString(at.getEpochSecond()))
                .put("recipient_id", "910000000001");
        item.putObject("pricing")
                .put("billable", true)
                .put("pricing_model", "PMP")
                .put("type", "regular")
                .put("category", "utility");
        if (errorCode != null) {
            ObjectNode error = item.putArray("errors")
                    .addObject()
                    .put("code", errorCode)
                    .put("title", "Message undeliverable to 910000000001: " + echo)
                    .put("message", "Message undeliverable to 910000000001: " + echo)
                    .put("href", "https://developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes/");
            error.putObject("error_data").put("details", "Message to 910000000001 failed: " + echo);
        }
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

    /**
     * A delivery holding one inbound message built field by field (step 2.4b): by default a text message "Hello" from
     * the fake customer 910000000001, profile name "Test Customer", with a random wamid and Meta's timestamp format.
     */
    public static Inbound inbound(String phoneNumberId) {
        return new Inbound(phoneNumberId);
    }

    /** One inbound message of any type; a null field leaves it out, as Meta would for a field it does not send. */
    public static final class Inbound {

        private final String phoneNumberId;
        private String wamid = WhatsAppPayloads.wamid();
        private String from = "910000000001";
        private String contactWaId;
        private String profileName = "Test Customer";
        private boolean contactsBlock = true;
        private String timestamp = "1763550000";
        private String type = "text";
        private String contentJson = "{\"body\":\"Hello\"}";

        private Inbound(String phoneNumberId) {
            this.phoneNumberId = phoneNumberId;
        }

        public Inbound wamid(String wamid) {
            this.wamid = wamid;
            return this;
        }

        /** The sender, as WhatsApp sends it: digits without a plus sign. */
        public Inbound from(String from) {
            this.from = from;
            return this;
        }

        /** The profile name on the sender's contacts entry; null sends the entry with no name. */
        public Inbound profileName(String profileName) {
            this.profileName = profileName;
            return this;
        }

        /** The wa_id of the contacts entry, when it should differ from the sender. */
        public Inbound contactWaId(String waId) {
            this.contactWaId = waId;
            return this;
        }

        /** Leaves out the contacts block entirely. */
        public Inbound withoutContacts() {
            this.contactsBlock = false;
            return this;
        }

        /** Unix seconds as Meta sends them, a string of digits; null leaves it out. */
        public Inbound timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Inbound sentAt(java.time.Instant at) {
            return timestamp(Long.toString(at.getEpochSecond()));
        }

        public Inbound text(String body) {
            return typed("text", JSON.createObjectNode().put("body", body).toString());
        }

        /** Any type, with the JSON of its object (for example {@code {"caption":"x"}} for an image); null for none. */
        public Inbound typed(String type, String contentJson) {
            this.type = type;
            this.contentJson = contentJson;
            return this;
        }

        public String wamid() {
            return wamid;
        }

        public String json() {
            WhatsAppPayloads delivery = new WhatsAppPayloads();
            ObjectNode value = delivery.messagesValue(phoneNumberId);
            if (contactsBlock) {
                ObjectNode contact = value.putArray("contacts").addObject();
                contact.put("wa_id", contactWaId != null ? contactWaId : from);
                ObjectNode profile = contact.putObject("profile");
                if (profileName != null) profile.put("name", profileName);
            }
            ObjectNode message = value.putArray("messages").addObject();
            if (from != null) message.put("from", from);
            message.put("id", wamid);
            if (timestamp != null) message.put("timestamp", timestamp);
            if (type != null) message.put("type", type);
            if (type != null && contentJson != null) message.set(type, JSON.readTree(contentJson));
            return delivery.change("messages", value).json();
        }
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
