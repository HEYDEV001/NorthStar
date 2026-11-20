package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.contacts.InboundMessageType;
import com.northstar.fakes.WhatsAppPayloads;
import com.northstar.fakes.WhatsAppPayloads.Inbound;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading one inbound message from its stored slice (step 2.4b): each type as decided on 20 Nov 2026, the sender's
 * phone (P4), and every way a slice can be unusable. Slices are built by the real splitter (step 2.2), so these are
 * exactly what the job reads.
 */
class WhatsAppInboundMessageTests {

    private static final String NUMBER = "pn-test-number";

    @Test
    void aTextMessageIsReadInFull() {
        Inbound inbound = WhatsAppPayloads.inbound(NUMBER).text("Need a shoot");

        WhatsAppInboundMessage message = usable(inbound);

        assertThat(message.wamid()).isEqualTo(inbound.wamid());
        assertThat(message.phoneNumberId()).isEqualTo(NUMBER);
        assertThat(message.from()).isEqualTo("+910000000001");
        assertThat(message.profileName()).isEqualTo("Test Customer");
        assertThat(message.sentAt()).isEqualTo(Instant.ofEpochSecond(1_763_550_000L));
        assertThat(message.type()).isEqualTo(InboundMessageType.TEXT);
        assertThat(message.body()).isEqualTo("Need a shoot");
        assertThat(message.mayOpenLead()).isTrue();
    }

    /** A type, its object, and what is stored: type, body and whether it may open a lead. */
    private record Case(String type, String content, InboundMessageType stored, String body, boolean opens) {}

    @Test
    void eachTypeIsStoredAsDecided() {
        List<Case> cases = List.of(
                new Case("text", "{\"body\":\"Hi\"}", InboundMessageType.TEXT, "Hi", true),
                new Case(
                        "interactive",
                        "{\"type\":\"button_reply\",\"button_reply\":{\"id\":\"quote:1\",\"title\":\"Accept\"}}",
                        InboundMessageType.INTERACTIVE,
                        "Accept",
                        true),
                new Case(
                        "interactive",
                        "{\"type\":\"list_reply\",\"list_reply\":{\"id\":\"slot-2\",\"title\":\"Weekend\","
                                + "\"description\":\"Sat or Sun\"}}",
                        InboundMessageType.INTERACTIVE,
                        "Weekend",
                        true),
                new Case(
                        "interactive",
                        "{\"type\":\"nfm_reply\",\"nfm_reply\":{\"response_json\":\"{}\"}}",
                        InboundMessageType.UNSUPPORTED,
                        null,
                        true),
                new Case(
                        "button",
                        "{\"payload\":\"yes\",\"text\":\"Yes, book it\"}",
                        InboundMessageType.INTERACTIVE,
                        "Yes, book it",
                        true),
                new Case(
                        "image",
                        "{\"id\":\"media-1\",\"mime_type\":\"image/jpeg\",\"caption\":\"Venue\"}",
                        InboundMessageType.IMAGE,
                        "Venue",
                        true),
                new Case(
                        "image",
                        "{\"id\":\"media-2\",\"mime_type\":\"image/png\"}",
                        InboundMessageType.IMAGE,
                        null,
                        true),
                new Case(
                        "document",
                        "{\"id\":\"media-3\",\"filename\":\"brief.pdf\",\"caption\":\"Brief\"}",
                        InboundMessageType.DOCUMENT,
                        "Brief",
                        true),
                new Case("audio", "{\"id\":\"media-4\",\"voice\":true}", InboundMessageType.AUDIO, null, true),
                new Case(
                        "location",
                        "{\"latitude\":19.07,\"longitude\":72.87,\"name\":\"Studio\",\"address\":\"Bandra\"}",
                        InboundMessageType.LOCATION,
                        null,
                        true),
                new Case(
                        "video",
                        "{\"id\":\"media-5\",\"caption\":\"Reel\"}",
                        InboundMessageType.UNSUPPORTED,
                        null,
                        true),
                new Case("sticker", "{\"id\":\"media-6\"}", InboundMessageType.UNSUPPORTED, null, true),
                new Case("contacts", null, InboundMessageType.UNSUPPORTED, null, true),
                new Case("order", "{\"catalog_id\":\"1\"}", InboundMessageType.UNSUPPORTED, null, true),
                new Case("unsupported", null, InboundMessageType.UNSUPPORTED, null, true),
                new Case("a_type_meta_adds_later", "{}", InboundMessageType.UNSUPPORTED, null, true),
                new Case(
                        "reaction",
                        "{\"message_id\":\"wamid.X\",\"emoji\":\"\\ud83d\\udc4d\"}",
                        InboundMessageType.UNSUPPORTED,
                        null,
                        false),
                new Case(
                        "system",
                        "{\"body\":\"Number changed\",\"type\":\"customer_changed_number\"}",
                        InboundMessageType.UNSUPPORTED,
                        null,
                        false));

        for (Case one : cases) {
            WhatsAppInboundMessage message =
                    usable(WhatsAppPayloads.inbound(NUMBER).typed(one.type(), one.content()));
            assertThat(message.type()).as(one.type() + " " + one.content()).isEqualTo(one.stored());
            assertThat(message.body()).as(one.type() + " " + one.content()).isEqualTo(one.body());
            assertThat(message.mayOpenLead()).as(one.type()).isEqualTo(one.opens());
            assertThat(message.toStored().type()).isEqualTo(one.stored());
        }
    }

    @Test
    void aSenderThatIsNotAnE164NumberIsRejectedAsContactPhoneInvalid() {
        for (String from : new String[] {
            null, "", "0123456789", "12345", "+910000000001", "91 0000 000001", "some.username", "9100000000011234"
        }) {
            assertThat(parse(WhatsAppPayloads.inbound(NUMBER).from(from)))
                    .as("from %s", from)
                    .isEqualTo(new WhatsAppInboundMessage.Rejected("contact_phone_invalid"));
        }
        assertThat(usable(WhatsAppPayloads.inbound(NUMBER).from("447700900123")).from())
                .isEqualTo("+447700900123");
    }

    @Test
    void anUnusableSliceIsRejectedAsPayloadInvalid() {
        Inbound inbound = WhatsAppPayloads.inbound(NUMBER);
        String slice = slice(inbound);
        List<String> unusable = List.of(
                parseAgainst("message:" + WhatsAppPayloads.wamid(), slice), // another message's key
                parseAgainst("message:" + inbound.wamid(), "not json"),
                parseAgainst("message:" + inbound.wamid(), "[]"),
                code(WhatsAppPayloads.inbound(NUMBER).timestamp(null)),
                code(WhatsAppPayloads.inbound(NUMBER).timestamp("soon")),
                code(WhatsAppPayloads.inbound(NUMBER).timestamp("0")),
                code(WhatsAppPayloads.inbound(NUMBER).timestamp("9999999999999")),
                code(WhatsAppPayloads.inbound(NUMBER).typed(null, null)),
                code(WhatsAppPayloads.inbound(NUMBER).typed("Text", "{\"body\":\"x\"}")),
                parseAgainst(
                        "message:" + inbound.wamid(),
                        slice.replace("\"phone_number_id\":\"" + NUMBER + "\"", "\"phone_number_id\":7")),
                parseAgainst(
                        "message:" + inbound.wamid(),
                        slice.replace("\"messages\":[{", "\"messages\":[{\"id\":\"wamid.OTHER\"},{")));
        assertThat(unusable).containsOnly("payload_invalid");
    }

    @Test
    void theTimestampIsUnixSecondsSentAsAStringOrANumber() {
        Inbound inbound = WhatsAppPayloads.inbound(NUMBER).timestamp("1763550000");
        String asNumber = slice(inbound).replace("\"timestamp\":\"1763550000\"", "\"timestamp\":1763550001");

        WhatsAppInboundMessage.Parsed parsed = WhatsAppInboundMessage.parse("message:" + inbound.wamid(), asNumber);

        assertThat(parsed)
                .isInstanceOfSatisfying(
                        WhatsAppInboundMessage.Usable.class,
                        usable ->
                                assertThat(usable.message().sentAt()).isEqualTo(Instant.ofEpochSecond(1_763_550_001L)));
    }

    @Test
    void theProfileNameIsTheSendersOnly() {
        assertThat(usable(WhatsAppPayloads.inbound(NUMBER).contactWaId("919999999999"))
                        .profileName())
                .as("another contact's entry")
                .isNull();
        assertThat(usable(WhatsAppPayloads.inbound(NUMBER).withoutContacts()).profileName())
                .isNull();
        assertThat(usable(WhatsAppPayloads.inbound(NUMBER).profileName(null)).profileName())
                .isNull();
    }

    @Test
    void nothingFromTheMessageAppearsInItsTextForm() {
        String marker = WhatsAppPayloads.marker();
        Inbound inbound =
                WhatsAppPayloads.inbound(NUMBER).profileName("Asha Marker").text(marker);
        WhatsAppInboundMessage message = usable(inbound);

        for (String text : List.of(message.toString(), message.toStored().toString())) {
            assertThat(text)
                    .doesNotContain(marker)
                    .doesNotContain("Asha Marker")
                    .doesNotContain("910000000001")
                    .doesNotContain(inbound.wamid())
                    .doesNotContain(NUMBER);
        }
    }

    // Helpers

    private static String slice(Inbound inbound) {
        List<WhatsAppDelivery.Item> items = WhatsAppDelivery.parse(
                        inbound.json().getBytes(StandardCharsets.UTF_8))
                .items();
        assertThat(items).hasSize(1);
        return items.getFirst().payloadJson();
    }

    private static WhatsAppInboundMessage.Parsed parse(Inbound inbound) {
        return WhatsAppInboundMessage.parse("message:" + inbound.wamid(), slice(inbound));
    }

    private static WhatsAppInboundMessage usable(Inbound inbound) {
        WhatsAppInboundMessage.Parsed parsed = parse(inbound);
        assertThat(parsed).isInstanceOf(WhatsAppInboundMessage.Usable.class);
        return ((WhatsAppInboundMessage.Usable) parsed).message();
    }

    private static String code(Inbound inbound) {
        return codeOf(parse(inbound));
    }

    private static String parseAgainst(String providerEventId, String payloadJson) {
        return codeOf(WhatsAppInboundMessage.parse(providerEventId, payloadJson));
    }

    private static String codeOf(WhatsAppInboundMessage.Parsed parsed) {
        return parsed instanceof WhatsAppInboundMessage.Rejected rejected ? rejected.errorCode() : "usable";
    }
}
