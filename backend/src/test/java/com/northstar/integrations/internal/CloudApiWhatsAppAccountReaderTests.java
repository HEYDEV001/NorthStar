package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.FailureClass;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The real number reader (step 2.8a, integration-specs.md section 2, Quality and limits) against a stand-in Graph API on
 * the JDK's own HTTP server, on loopback (the network guard blocks every other host; no new dependency): what it asks
 * for, where and with which token; Meta's answers with and without the limit field, and a version that refuses it; how
 * failures are classified; and what it logs. Answers are shaped as Meta's messaging-limits page shows them (checked 22
 * Nov 2026).
 */
@ExtendWith(OutputCaptureExtension.class)
class CloudApiWhatsAppAccountReaderTests {

    private static final String TOKEN = "EAAG-test-token-" + UUID.randomUUID();
    private static final String PHONE_NUMBER_ID = "106540352242922";
    private static final String BOTH_FIELDS = "fields=quality_rating,whatsapp_business_manager_messaging_limit";

    private HttpServer server;
    private final Deque<Answer> answers = new ArrayDeque<>();
    private final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    private final CountDownLatch never = new CountDownLatch(1);

    /** What the stand-in answers next: status, body, extra headers, or never (to force a client timeout). */
    private record Answer(int status, String body, Map<String, String> headers, boolean hang) {}

    @BeforeEach
    void startStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.add(Map.of(
                    "method", exchange.getRequestMethod(),
                    "path", exchange.getRequestURI().getRawPath(),
                    "query", String.valueOf(exchange.getRequestURI().getRawQuery()),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Answer next;
            synchronized (answers) {
                next = answers.size() > 1 ? answers.poll() : answers.peek();
            }
            if (next.hang()) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            next.headers()
                    .forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(next.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStandIn() {
        never.countDown();
        server.stop(0);
    }

    @Test
    void aNumberIsOneGetOfBothFieldsOnTheVersionedNumberWithTheBusinessToken() {
        answer(200, number("GREEN", "TIER_250"));

        WhatsAppAccountReader.NumberRead read = read();

        assertThat(read).isEqualTo(new WhatsAppAccountReader.NumberHealth("GREEN", "TIER_250", false));
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst())
                .containsEntry("method", "GET")
                .containsEntry("path", "/v26.0/" + PHONE_NUMBER_ID)
                .containsEntry("query", BOTH_FIELDS)
                .containsEntry("authorization", "Bearer " + TOKEN)
                .containsEntry("body", "");
    }

    @Test
    void theVersionIsTheConfiguredOne() {
        answer(200, number("GREEN", "TIER_250"));

        reader("v27.0", Duration.ofSeconds(5)).readNumber(UUID.randomUUID(), PHONE_NUMBER_ID, token());

        assertThat(seen.getFirst()).containsEntry("path", "/v27.0/" + PHONE_NUMBER_ID);
    }

    @Test
    void aLimitMissingFromTheAnswerIsAbsentNotAFailure() {
        answer(200, number("YELLOW", null));
        assertThat(read()).isEqualTo(new WhatsAppAccountReader.NumberHealth("YELLOW", null, false));

        answer(200, "{\"id\": \"" + PHONE_NUMBER_ID + "\"}");
        assertThat(read()).as("neither field").isEqualTo(new WhatsAppAccountReader.NumberHealth(null, null, false));

        answer(200, "{\"quality_rating\": 3, \"whatsapp_business_manager_messaging_limit\": 2000}");
        assertThat(read())
                .as("values that are not text are absent, never guessed")
                .isEqualTo(new WhatsAppAccountReader.NumberHealth(null, null, false));
    }

    @Test
    void aVersionThatRefusesTheLimitFieldIsReadAgainWithoutIt() {
        answer(400, nonexistingField());
        thenAnswer(200, "{\"quality_rating\": \"RED\", \"id\": \"" + PHONE_NUMBER_ID + "\"}");

        assertThat(read()).isEqualTo(new WhatsAppAccountReader.NumberHealth("RED", null, true));
        assertThat(seen)
                .extracting(request -> request.get("query"))
                .containsExactly(BOTH_FIELDS, "fields=quality_rating");
        assertThat(seen).allSatisfy(request -> assertThat(request).containsEntry("authorization", "Bearer " + TOKEN));
    }

    @Test
    void aNarrowerReadThatFailsTooIsClassifiedAsUsual() {
        answer(400, error(100, "Invalid parameter"));

        assertThat(read()).isEqualTo(failed(FailureClass.PERMANENT, "invalid_parameter", null));
        assertThat(seen).as("one narrower read, never more").hasSize(2);

        seen.clear();
        answer(400, nonexistingField());
        thenAnswer(401, error(190, "Error validating access token"));
        assertThat(read()).isEqualTo(failed(FailureClass.AUTH, "token_expired", null));
        assertThat(seen).hasSize(2);
    }

    @Test
    void failuresAreClassifiedByMetasCodeThenByTheStatus() {
        answer(401, error(190, "Error validating access token"));
        assertThat(read()).isEqualTo(failed(FailureClass.AUTH, "token_expired", null));

        answer(403, error(200, "Permissions error"));
        assertThat(read()).isEqualTo(failed(FailureClass.AUTH, "permission_missing", null));

        answer(400, error(80008, "Too many calls to this WhatsApp Business account"));
        assertThat(read()).isEqualTo(failed(FailureClass.RETRYABLE, "rate_limited_waba_management", null));

        answer(400, error(4, "Application request limit reached"), Map.of("Retry-After", "30"));
        assertThat(read()).isEqualTo(failed(FailureClass.RETRYABLE, "rate_limited_app", Duration.ofSeconds(30)));

        answer(429, "", Map.of("Retry-After", "45"));
        assertThat(read()).isEqualTo(failed(FailureClass.RETRYABLE, "http_429", Duration.ofSeconds(45)));

        answer(503, "Service Unavailable");
        assertThat(read()).isEqualTo(failed(FailureClass.RETRYABLE, "http_503", null));

        answer(404, "{\"error\": {\"message\": \"Unknown path\"}}");
        assertThat(read()).isEqualTo(failed(FailureClass.PERMANENT, "http_404", null));
        assertThat(seen).as("only error 100 is read again").hasSize(7);
    }

    @Test
    void aSuccessThatIsNotAJsonObjectIsRetryableAsAReadChangesNothing() {
        for (String body : List.of("not json", "[]", "")) {
            answer(200, body);
            assertThat(read()).as(body).isEqualTo(failed(FailureClass.RETRYABLE, "response_unreadable", null));
        }
    }

    @Test
    void timeoutsAndLostConnectionsAreRetryable() throws IOException {
        synchronized (answers) {
            answers.add(new Answer(0, "", Map.of(), true));
        }
        assertThat(reader("v26.0", Duration.ofMillis(300)).readNumber(UUID.randomUUID(), PHONE_NUMBER_ID, token()))
                .isEqualTo(failed(FailureClass.RETRYABLE, "timeout", null));

        HttpServer closed = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = closed.getAddress().getPort();
        closed.start();
        closed.stop(0);
        CloudApiWhatsAppAccountReader nobodyThere = new CloudApiWhatsAppAccountReader(
                http(), URI.create("http://127.0.0.1:" + port), "v26.0", Duration.ofSeconds(2));
        assertThat(nobodyThere.readNumber(UUID.randomUUID(), PHONE_NUMBER_ID, token()))
                .isEqualTo(failed(FailureClass.RETRYABLE, "connection_error", null));
    }

    @Test
    void aRedirectIsNeverFollowedSoTheTokenGoesNowhereElse() {
        answer(302, "", Map.of("Location", base() + "/v26.0/elsewhere"));

        assertThat(read()).isEqualTo(failed(FailureClass.PERMANENT, "http_302", null));
        assertThat(seen).as("one request, to the configured path").hasSize(1);
    }

    @Test
    void aPhoneNumberIdThatIsNotDigitsCallsNothing() {
        answer(200, number("GREEN", "TIER_250"));
        for (String id : List.of("pn-123", "123/../456", "123?fields=access_token", "", "١٢٣")) {
            assertThat(reader("v26.0", Duration.ofSeconds(5)).readNumber(UUID.randomUUID(), id, token()))
                    .as(id)
                    .isEqualTo(failed(FailureClass.PERMANENT, "phone_number_id_invalid", null));
        }
        assertThat(reader("v26.0", Duration.ofSeconds(5)).readNumber(UUID.randomUUID(), null, token()))
                .isEqualTo(failed(FailureClass.PERMANENT, "phone_number_id_invalid", null));
        assertThat(seen).isEmpty();
    }

    @Test
    void logsCarryIdsStatusesAndCodesOnly(CapturedOutput output) {
        UUID accountId = UUID.randomUUID();
        answer(400, nonexistingField());
        thenAnswer(200, number("GREEN", null));
        reader("v26.0", Duration.ofSeconds(5)).readNumber(accountId, PHONE_NUMBER_ID, token());
        answer(200, number("YELLOW", "TIER_10K"));
        read();
        answer(400, error(131000, "Something went wrong for " + PHONE_NUMBER_ID));
        reader("v26.0", Duration.ofSeconds(5)).readNumber(accountId, PHONE_NUMBER_ID, token());

        assertThat(output.getAll())
                .contains("whatsapp.cloud_api.number_read account_id=" + accountId + " status=200 limit_field=refused")
                .contains("limit_field=present")
                .contains("whatsapp.cloud_api.read_failed account_id=" + accountId
                        + " status=400 meta_code=131000 class=retryable code=meta_unknown_error"
                        + " fbtrace_id=Az8or2yhqkZfEZ-_4Qn_Bam")
                .doesNotContain(TOKEN)
                .doesNotContain(PHONE_NUMBER_ID)
                .doesNotContain("TIER_10K")
                .doesNotContain("YELLOW")
                .doesNotContain("nonexisting field")
                .doesNotContain("OAuthException");
    }

    // Template listing (step 2.8b).

    private static final String WABA_ID = "102290129340398";
    private static final String TEMPLATE_QUERY =
            "fields=id,name,language,status,category,parameter_format,components&limit=100";

    @Test
    void aTemplatePageIsOneGetOfItsFieldsOnTheVersionedAccountWithTheBusinessToken() {
        answer(
                200,
                templatePage(
                        "[" + listed("\"1387372356726668\"", "reservation_confirmation", "APPROVED") + ","
                                + listed("1387372356726669", "shoot_reminder", "PAUSED") + "]",
                        "\"https://graph.facebook.com/v26.0/" + WABA_ID + "/message_templates?after=QVFIUmlZAZD\"",
                        "QVFIUmlZAZD"));

        WhatsAppAccountReader.TemplatePageRead read = list(null);

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst())
                .containsEntry("method", "GET")
                .containsEntry("path", "/v26.0/" + WABA_ID + "/message_templates")
                .containsEntry("query", TEMPLATE_QUERY)
                .containsEntry("authorization", "Bearer " + TOKEN);
        assertThat(read).isInstanceOf(WhatsAppAccountReader.TemplatePage.class);
        WhatsAppAccountReader.TemplatePage page = (WhatsAppAccountReader.TemplatePage) read;
        assertThat(page.after()).isEqualTo("QVFIUmlZAZD");
        assertThat(page.unreadable()).isZero();
        assertThat(page.templates())
                .containsExactly(
                        new WhatsAppAccountReader.ListedTemplate(
                                "1387372356726668",
                                "reservation_confirmation",
                                "en_US",
                                "APPROVED",
                                "UTILITY",
                                "POSITIONAL",
                                "Hi {{1}}, your shoot is on {{2}}."),
                        new WhatsAppAccountReader.ListedTemplate(
                                "1387372356726669",
                                "shoot_reminder",
                                "en_US",
                                "PAUSED",
                                "UTILITY",
                                "POSITIONAL",
                                "Hi {{1}}, your shoot is on {{2}}."));
    }

    @Test
    void theNextPageIsAskedForWithMetasCursorAndMetasNextUrlIsNeverFollowed() {
        answer(200, templatePage("[]", "\"http://127.0.0.2:9/elsewhere?access_token=leak\"", "QVFIUmlZAZD+/=="));

        WhatsAppAccountReader.TemplatePageRead first = list(null);
        list(((WhatsAppAccountReader.TemplatePage) first).after());

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1))
                .containsEntry("path", "/v26.0/" + WABA_ID + "/message_templates")
                .containsEntry("query", TEMPLATE_QUERY + "&after=QVFIUmlZAZD%2B%2F%3D%3D");
    }

    @Test
    void aPageWithoutNextIsTheLast() {
        answer(200, templatePage("[]", null, "QVFIUmlZAZD"));
        assertThat(list(null)).isEqualTo(new WhatsAppAccountReader.TemplatePage(java.util.List.of(), 0, null));

        answer(200, "{\"data\": []}");
        assertThat(list(null))
                .as("no paging at all")
                .isEqualTo(new WhatsAppAccountReader.TemplatePage(java.util.List.of(), 0, null));
    }

    @Test
    void entriesWithoutAUsableIdAreCountedAndNeverGuessed() {
        answer(
                200,
                templatePage(
                        "[{\"name\": \"no_id\"}, {\"id\": \"abc\"}, {\"id\": 0}, {\"id\": {}}, \"not an object\", "
                                + "{\"id\": 1387372356726670, \"name\": \"numeric_id\"}]",
                        null,
                        null));

        WhatsAppAccountReader.TemplatePage page = (WhatsAppAccountReader.TemplatePage) list(null);

        assertThat(page.unreadable()).isEqualTo(5);
        assertThat(page.templates())
                .containsExactly(new WhatsAppAccountReader.ListedTemplate(
                        "1387372356726670", "numeric_id", null, null, null, null, null));
    }

    @Test
    void aSuccessThatIsNotAPageIsRetryable() {
        for (String body : List.of(
                "not json",
                "{}",
                "{\"data\": {}}",
                templatePage("[]", "\"https://graph.facebook.com/next\"", null),
                templatePage("[]", "\"https://graph.facebook.com/next\"", "cursor with spaces"))) {
            answer(200, body);
            assertThat(list(null)).as(body).isEqualTo(failed(FailureClass.RETRYABLE, "response_unreadable", null));
        }
    }

    @Test
    void listingFailuresAreClassifiedAsReadsAre() {
        answer(401, error(190, "Error validating access token"));
        assertThat(list(null)).isEqualTo(failed(FailureClass.AUTH, "token_expired", null));
        answer(403, error(200, "Permissions error"));
        assertThat(list(null)).isEqualTo(failed(FailureClass.AUTH, "permission_missing", null));
        answer(400, error(80008, "Too many calls to this WhatsApp Business account"));
        assertThat(list(null)).isEqualTo(failed(FailureClass.RETRYABLE, "rate_limited_waba_management", null));
        answer(400, error(100, "Invalid parameter"));
        assertThat(list(null))
                .as("no narrower read for a listing")
                .isEqualTo(failed(FailureClass.PERMANENT, "invalid_parameter", null));
        answer(503, "Service Unavailable");
        assertThat(list(null)).isEqualTo(failed(FailureClass.RETRYABLE, "http_503", null));
        assertThat(seen).hasSize(5);
    }

    @Test
    void aWabaIdOrCursorThatIsNotOneCallsNothing() {
        answer(200, templatePage("[]", null, null));
        for (String id : java.util.Arrays.asList("waba-1", "123/../456", "123?fields=access_token", "", null)) {
            assertThat(reader("v26.0", Duration.ofSeconds(5)).listTemplates(id, null, token()))
                    .as(String.valueOf(id))
                    .isEqualTo(failed(FailureClass.PERMANENT, "waba_id_invalid", null));
        }
        for (String cursor : List.of("a&access_token=x", "a b", "", "x".repeat(1025))) {
            assertThat(list(cursor)).as(cursor).isEqualTo(failed(FailureClass.PERMANENT, "cursor_invalid", null));
        }
        assertThat(seen).isEmpty();
    }

    @Test
    void listingLogsCarryCountsStatusesAndCodesOnly(CapturedOutput output) {
        answer(
                200,
                templatePage(
                        "[" + listed("\"1387372356726668\"", "reservation_confirmation", "APPROVED") + "]",
                        "\"https://graph.facebook.com/next\"",
                        "QVFIUmlZAZD"));
        list(null);
        answer(400, error(80008, "Too many calls for " + WABA_ID));
        list("QVFIUmlZAZD");

        assertThat(output.getAll())
                .contains("whatsapp.cloud_api.templates_read status=200 templates=1 unreadable=0 last_page=false")
                .contains("whatsapp.cloud_api.templates_failed status=400 meta_code=80008 class=retryable"
                        + " code=rate_limited_waba_management")
                .doesNotContain(TOKEN)
                .doesNotContain(WABA_ID)
                .doesNotContain("QVFIUmlZAZD")
                .doesNotContain("reservation_confirmation")
                .doesNotContain("1387372356726668")
                .doesNotContain("your shoot");
    }

    private WhatsAppAccountReader.TemplatePageRead list(String after) {
        return reader("v26.0", Duration.ofSeconds(5)).listTemplates(WABA_ID, after, token());
    }

    /** Meta's listing: these entries, and paging with this next URL and after cursor (null leaves either out). */
    private static String templatePage(String data, String nextJson, String after) {
        String cursors = after == null ? "{}" : "{\"before\": \"QVFIUm5\", \"after\": \"" + after + "\"}";
        String next = nextJson == null ? "" : ", \"next\": " + nextJson;
        return "{\"data\": " + data + ", \"paging\": {\"cursors\": " + cursors + next + "}}";
    }

    /** One listed template as Meta's template-management page shows it: header, body and buttons. */
    private static String listed(String idJson, String name, String status) {
        return "{\"name\": \"" + name + "\", \"parameter_format\": \"POSITIONAL\", \"components\": ["
                + "{\"type\": \"HEADER\", \"format\": \"TEXT\", \"text\": \"Booking\"},"
                + "{\"type\": \"BODY\", \"text\": \"Hi {{1}}, your shoot is on {{2}}.\","
                + " \"example\": {\"body_text\": [[\"Asha\", \"Saturday\"]]}},"
                + "{\"type\": \"BUTTONS\", \"buttons\": [{\"type\": \"QUICK_REPLY\", \"text\": \"Confirm\"}]}],"
                + " \"language\": \"en_US\", \"status\": \"" + status + "\", \"category\": \"UTILITY\", \"id\": "
                + idJson
                + "}";
    }

    // Helpers.

    private WhatsAppAccountReader.NumberRead read() {
        return reader("v26.0", Duration.ofSeconds(5)).readNumber(UUID.randomUUID(), PHONE_NUMBER_ID, token());
    }

    private CloudApiWhatsAppAccountReader reader(String version, Duration requestTimeout) {
        return new CloudApiWhatsAppAccountReader(http(), base(), version, requestTimeout);
    }

    private static Secret token() {
        return Secret.ofUtf8(TOKEN);
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static HttpClient http() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** From now on the stand-in answers this to every request. */
    private void answer(int status, String body) {
        answer(status, body, Map.of());
    }

    private void answer(int status, String body, Map<String, String> headers) {
        synchronized (answers) {
            answers.clear();
            answers.add(new Answer(status, body, headers, false));
        }
    }

    /** After the answers queued so far, this one; the last one queued repeats for every later request. */
    private void thenAnswer(int status, String body) {
        synchronized (answers) {
            answers.add(new Answer(status, body, Map.of(), false));
        }
    }

    /** Meta's phone number node with these fields (null leaves one out). */
    private static String number(String quality, String limit) {
        StringBuilder json = new StringBuilder("{");
        if (quality != null)
            json.append("\"quality_rating\": \"").append(quality).append("\", ");
        if (limit != null) {
            json.append("\"whatsapp_business_manager_messaging_limit\": \"")
                    .append(limit)
                    .append("\", ");
        }
        return json.append("\"id\": \"").append(PHONE_NUMBER_ID).append("\"}").toString();
    }

    /** The Graph API's answer to a field this version does not have. */
    private static String nonexistingField() {
        return error(
                100,
                "Tried accessing nonexisting field (whatsapp_business_manager_messaging_limit) on node type"
                        + " (WhatsAppBusinessPhoneNumber)");
    }

    /** Meta's error answer, with a message that echoes the request, as Meta's can. */
    private static String error(int code, String title) {
        return "{\"error\": {\"message\": \"(#" + code + ") " + title + "\", \"type\": \"OAuthException\", \"code\": "
                + code + ", \"fbtrace_id\": \"Az8or2yhqkZfEZ-_4Qn_Bam\"}}";
    }

    private static WhatsAppAccountReader.Failed failed(FailureClass failureClass, String code, Duration retryAfter) {
        return new WhatsAppAccountReader.Failed(new ActionOutcome.Failed(failureClass, code, retryAfter));
    }
}
