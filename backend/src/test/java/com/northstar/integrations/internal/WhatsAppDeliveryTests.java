package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.fakes.WhatsAppPayloads;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Splitting a verified delivery into items, their routes, dedupe keys and slices (steps 2.2 and 2.8a). */
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
        assertThat(parsed.items()).extracting(WhatsAppDelivery.Item::routingKey).containsOnly(number);
        assertThat(parsed.items())
                .extracting(WhatsAppDelivery.Item::route)
                .containsOnly(WhatsAppDelivery.Route.PHONE_NUMBER);
        assertThat(parsed.ignored()).isZero();
    }

    @Test
    void P1_aCapabilityUpdateIsRoutedByWabaAndKeyedByFieldWabaTimeAndDigest() {
        String waba = WhatsAppPayloads.wabaId();
        WhatsAppDelivery.Parsed parsed = parse(WhatsAppPayloads.delivery()
                .capabilityUpdate(waba, 1_739_321_024L, "TIER_2K")
                .json());

        assertThat(parsed.ignored()).isZero();
        assertThat(parsed.items()).hasSize(1);
        WhatsAppDelivery.Item item = parsed.items().getFirst();
        assertThat(item.route()).isEqualTo(WhatsAppDelivery.Route.WABA);
        assertThat(item.routingKey()).isEqualTo(waba);
        assertThat(item.providerEventId())
                .matches("business_capability_update:" + waba + ":1739321024:[0-9a-f]{32}")
                .hasSizeLessThanOrEqualTo(200);

        JsonNode slice = ProviderJson.MAPPER.readTree(item.payloadJson());
        assertThat(slice.get("object").stringValue()).isEqualTo("whatsapp_business_account");
        assertThat(slice.at("/entry/0/id").stringValue()).isEqualTo(waba);
        assertThat(slice.at("/entry/0/time").longValue()).isEqualTo(1_739_321_024L);
        assertThat(slice.at("/entry/0/changes/0/field").stringValue()).isEqualTo("business_capability_update");
        assertThat(slice.at("/entry/0/changes/0/value/max_daily_conversations_per_business")
                        .stringValue())
                .isEqualTo("TIER_2K");
    }

    @Test
    void P1_theSameAccountChangeHasTheSameKeyHoweverItIsBatchedAndAnotherChangeAnother() {
        String waba = WhatsAppPayloads.wabaId();
        String other = WhatsAppPayloads.wabaId();
        String alone = parse(WhatsAppPayloads.delivery()
                        .capabilityUpdate(waba, 1_739_321_024L, "TIER_2K")
                        .json())
                .items()
                .getFirst()
                .providerEventId();
        List<String> batched = parse(WhatsAppPayloads.delivery()
                        .message(WhatsAppPayloads.phoneNumberId(), WhatsAppPayloads.wamid(), "Hi")
                        .capabilityUpdate(other, 1_739_321_024L, "TIER_2K")
                        .capabilityUpdate(waba, 1_739_321_024L, "TIER_2K")
                        .capabilityUpdate(waba, 1_739_321_024L, "TIER_10K")
                        .capabilityUpdate(waba, 1_739_321_025L, "TIER_2K")
                        .json())
                .items()
                .stream()
                .map(WhatsAppDelivery.Item::providerEventId)
                .toList();

        assertThat(batched).hasSize(5).doesNotHaveDuplicates().contains(alone);
        assertThat(batched.stream().filter(key -> key.startsWith("business_capability_update:" + waba + ":")))
                .as("another value in the same second, and the same value a second later, are other changes")
                .hasSize(3);
    }

    @Test
    void P9_anAccountChangesSliceHoldsOnlyItsOwnEntryAndChange() {
        String wabaA = WhatsAppPayloads.wabaId();
        String wabaB = WhatsAppPayloads.wabaId();
        String number = WhatsAppPayloads.phoneNumberId();
        String marker = WhatsAppPayloads.marker();
        String body = """
                {"object":"whatsapp_business_account","entry":[
                  {"id":"%s","time":1739321024,"changes":[
                    {"field":"messages","value":{"metadata":{"phone_number_id":"%s"},
                      "messages":[{"id":"%s","type":"text","text":{"body":"%s"}}]}},
                    {"field":"business_capability_update","value":{"max_daily_conversations_per_business":"TIER_10K"}}]},
                  {"id":"%s","time":1739321030,"changes":[
                    {"field":"business_capability_update","value":{"max_daily_conversations_per_business":"TIER_250"}}]}]}""".formatted(wabaA, number, WhatsAppPayloads.wamid(), marker, wabaB);

        WhatsAppDelivery.Parsed parsed = parse(body);

        assertThat(parsed.items()).hasSize(3);
        List<WhatsAppDelivery.Item> accountItems = parsed.items().stream()
                .filter(item -> item.route() == WhatsAppDelivery.Route.WABA)
                .toList();
        assertThat(accountItems).extracting(WhatsAppDelivery.Item::routingKey).containsExactly(wabaA, wabaB);
        for (WhatsAppDelivery.Item item : accountItems) {
            String otherWaba = item.routingKey().equals(wabaA) ? wabaB : wabaA;
            assertThat(item.payloadJson())
                    .doesNotContain(otherWaba)
                    .doesNotContain(number)
                    .doesNotContain(marker)
                    .doesNotContain("\"messages\"");
            assertThat(ProviderJson.MAPPER.readTree(item.payloadJson()).at("/entry/0/changes"))
                    .hasSize(1);
        }
    }

    @Test
    void accountChangesWithoutAUsableWabaIdOrTimeAreIgnored() {
        String waba = WhatsAppPayloads.wabaId();
        for (String entry : List.of(
                "{\"time\":1739321024", // no WABA ID
                "{\"id\":\"\",\"time\":1739321024",
                "{\"id\":\"waba:with:colons\",\"time\":1739321024",
                "{\"id\":12345,\"time\":1739321024",
                "{\"id\":\"" + waba + "\"", // no time
                "{\"id\":\"" + waba + "\",\"time\":\"1739321024\"",
                "{\"id\":\"" + waba + "\",\"time\":0",
                "{\"id\":\"" + waba + "\",\"time\":1.5")) {
            String body = "{\"object\":\"whatsapp_business_account\",\"entry\":[" + entry
                    + ",\"changes\":[{\"field\":\"business_capability_update\","
                    + "\"value\":{\"max_daily_conversations_per_business\":\"TIER_2K\"}}]}]}";
            WhatsAppDelivery.Parsed parsed = parse(body);
            assertThat(parsed.items()).as(entry).isEmpty();
            assertThat(parsed.ignored()).as(entry).isEqualTo(1);
        }
    }

    @Test
    void templateChangesAreStoredAndPhoneNumberQualityAndAccountChangesAreNot() {
        String waba = WhatsAppPayloads.wabaId();
        WhatsAppDelivery.Parsed parsed = parse(WhatsAppPayloads.delivery()
                .accountChange(
                        waba,
                        1_748_454_394L,
                        "phone_number_quality_update",
                        WhatsAppPayloads.object()
                                .put("display_phone_number", "15550783881")
                                .put("event", "THROUGHPUT_UPGRADE")
                                .put("max_daily_conversations_per_business", "TIER_UNLIMITED"))
                .accountChange(
                        waba,
                        1_748_454_394L,
                        "message_template_status_update",
                        WhatsAppPayloads.object().put("event", "APPROVED"))
                .accountChange(waba, 1_748_454_394L, "template_category_update", WhatsAppPayloads.object())
                .accountChange(waba, 1_748_454_394L, "account_update", WhatsAppPayloads.object())
                .accountChange(waba, 1_748_454_394L, "message_template_quality_update", WhatsAppPayloads.object())
                .json());

        assertThat(parsed.items())
                .as("template status and category, since step 2.8b")
                .extracting(WhatsAppDelivery.Item::providerEventId)
                .satisfiesExactly(
                        key -> assertThat(key).startsWith("message_template_status_update:" + waba + ":1748454394:"),
                        key -> assertThat(key).startsWith("template_category_update:" + waba + ":1748454394:"));
        assertThat(parsed.items()).extracting(WhatsAppDelivery.Item::route).containsOnly(WhatsAppDelivery.Route.WABA);
        assertThat(parsed.ignored()).isEqualTo(3);
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
                    .isEqualTo(item.routingKey());
            assertThat(value.get("messages")).hasSize(1);
            assertThat(item.payloadBytes()).isEqualTo(item.payloadJson().getBytes(StandardCharsets.UTF_8).length);
            String other = item.routingKey().equals(numberA) ? numberB : numberA;
            String otherMarker = item.routingKey().equals(numberA) ? markerB : markerA;
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
