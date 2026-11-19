package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.fakes.WhatsAppPayloads;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Splitting a verified delivery into items, their dedupe keys and their slices (step 2.2). */
class WhatsAppDeliveryTests {

    private static WhatsAppDelivery.Parsed parse(String body) {
        return WhatsAppDelivery.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void P1_aMessageIsKeyedByItsWamidAndAStatusByWamidAndStatus() {
        String number = WhatsAppPayloads.phoneNumberId();
        String inbound = WhatsAppPayloads.wamid();
        String outbound = WhatsAppPayloads.wamid();
        WhatsAppDelivery.Parsed parsed = parse(WhatsAppPayloads.delivery()
                .message(number, inbound, "Hello")
                .status(number, outbound, "delivered")
                .status(number, outbound, "read")
                .json());

        assertThat(parsed.readable()).isTrue();
        assertThat(parsed.items())
                .extracting(WhatsAppDelivery.Item::providerEventId)
                .containsExactly(
                        "message:" + inbound, "status:" + outbound + ":delivered", "status:" + outbound + ":read");
        assertThat(parsed.items())
                .extracting(WhatsAppDelivery.Item::phoneNumberId)
                .containsOnly(number);
        assertThat(parsed.ignored()).isZero();
    }

    @Test
    void P1_theSameItemHasTheSameKeyHoweverItIsBatched() {
        String number = WhatsAppPayloads.phoneNumberId();
        String first = WhatsAppPayloads.wamid();
        String second = WhatsAppPayloads.wamid();
        List<String> alone = parse(
                        WhatsAppPayloads.delivery().message(number, first, "Hi").json())
                .items()
                .stream()
                .map(WhatsAppDelivery.Item::providerEventId)
                .toList();
        List<String> batched =
                parse(WhatsAppPayloads.delivery()
                                .messages(number, List.of(second, first), "Hi")
                                .json())
                        .items()
                        .stream()
                        .map(WhatsAppDelivery.Item::providerEventId)
                        .toList();
        assertThat(batched).containsAll(alone);
    }

    @Test
    void P9_eachSliceHoldsOnlyItsOwnNumbersDataAndOneItem() {
        String numberA = WhatsAppPayloads.phoneNumberId();
        String numberB = WhatsAppPayloads.phoneNumberId();
        String markerA = WhatsAppPayloads.marker();
        String markerB = WhatsAppPayloads.marker();
        String wamidA1 = WhatsAppPayloads.wamid();
        String wamidA2 = WhatsAppPayloads.wamid();
        WhatsAppDelivery.Parsed parsed = parse(WhatsAppPayloads.delivery()
                .messages(numberA, List.of(wamidA1, wamidA2), markerA)
                .message(numberB, WhatsAppPayloads.wamid(), markerB)
                .inOneEntry()
                .json());

        assertThat(parsed.items()).hasSize(3);
        for (WhatsAppDelivery.Item item : parsed.items()) {
            JsonNode slice = ProviderJson.MAPPER.readTree(item.payloadJson());
            assertThat(slice.get("object").stringValue()).isEqualTo("whatsapp_business_account");
            assertThat(slice.get("entry")).hasSize(1);
            JsonNode change = slice.get("entry").get(0).get("changes");
            assertThat(change).hasSize(1);
            JsonNode value = change.get(0).get("value");
            assertThat(value.get("metadata").get("phone_number_id").stringValue())
                    .isEqualTo(item.phoneNumberId());
            assertThat(value.get("messages")).hasSize(1);
            assertThat(item.payloadBytes()).isEqualTo(item.payloadJson().getBytes(StandardCharsets.UTF_8).length);
            String other = item.phoneNumberId().equals(numberA) ? numberB : numberA;
            String otherMarker = item.phoneNumberId().equals(numberA) ? markerB : markerA;
            assertThat(item.payloadJson()).doesNotContain(other).doesNotContain(otherMarker);
        }
        // The two messages of number A are in separate slices.
        assertThat(parsed.items().get(0).payloadJson()).contains(wamidA1).doesNotContain(wamidA2);
    }

    @Test
    void otherFieldsErrorsAndItemsWithoutAnIdAreIgnored() {
        String number = WhatsAppPayloads.phoneNumberId();
        String body = WhatsAppPayloads.delivery()
                .otherField("message_template_status_update")
                .otherField("phone_number_quality_update")
                .errorsOnly(number)
                .json();
        WhatsAppDelivery.Parsed parsed = parse(body);
        assertThat(parsed.readable()).isTrue();
        assertThat(parsed.items()).isEmpty();
        assertThat(parsed.ignored()).isEqualTo(3);

        String noIds = """
                {"object":"whatsapp_business_account","entry":[{"id":"w","changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"%s"},
                  "messages":[{"type":"text"},{"id":""},{"id":42}],
                  "statuses":[{"id":"wamid.X"},{"id":"wamid.Y","status":"Delivered!"}]}}]}]}""".formatted(number);
        WhatsAppDelivery.Parsed none = parse(noIds);
        assertThat(none.items()).isEmpty();
        assertThat(none.ignored()).isEqualTo(5);

        String noNumber = """
                {"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{
                  "messages":[{"id":"wamid.Z"}]}}]}]}""";
        assertThat(parse(noNumber).items()).isEmpty();
        assertThat(parse(noNumber).ignored()).isEqualTo(1);
    }

    @Test
    void aBodyThatIsNotJsonOrNotAWhatsAppAccountIsUnreadable() {
        for (String body : List.of(
                "", "not json", "[]", "42", "{\"object\":\"page\",\"entry\":[]}", "{\"entry\":[]}", "{\"object\":1}")) {
            assertThat(parse(body).readable()).as(body).isFalse();
        }
        assertThat(parse("{\"object\":\"whatsapp_business_account\"}").readable())
                .isTrue();
    }

    @Test
    void nulCharactersAndLoneSurrogatesAreReplacedSoJsonbCanHoldThem() {
        // Meta sends escaped Unicode, so these arrive as JSON escapes: a NUL, a lone high and a lone low surrogate,
        // and a valid pair (an emoji), which is kept.
        String number = WhatsAppPayloads.phoneNumberId();
        String escaped = "a\\u0000b\\uD800c\\uDC00d\\uD83D\\uDE00e";
        String body = WhatsAppPayloads.delivery()
                .message(number, WhatsAppPayloads.wamid(), "PLACEHOLDER")
                .json()
                .replace("PLACEHOLDER", escaped);
        String slice = parse(body).items().getFirst().payloadJson();
        JsonNode text = ProviderJson.MAPPER.readTree(slice).at("/entry/0/changes/0/value/messages/0/text/body");
        String replacement = String.valueOf((char) 0xFFFD);
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(text.stringValue())
                .isEqualTo("a" + replacement + "b" + replacement + "c" + replacement + "d" + emoji + "e");
        assertThat(slice).doesNotContain("\\u0000");
    }
}
