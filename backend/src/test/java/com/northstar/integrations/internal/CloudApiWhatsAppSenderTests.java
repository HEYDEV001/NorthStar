package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.contacts.TemplateParameters;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The real Cloud API adapter (step 2.6b, integration-specs.md section 2) against a stand-in Graph API on the JDK's own
 * HTTP server, on loopback (the network guard blocks every other host; no new dependency): what it sends, where and
 * with which token, how each kind of answer is classified, and what it logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class CloudApiWhatsAppSenderTests {

    private static final String TOKEN = "EAAG-test-token-" + UUID.randomUUID();
    private static final String PHONE_NUMBER_ID = "106540352242922";
    private static final String TO = "+919876543210";
    private static final String TEXT = "Your shoot is booked for Saturday";
    private static final String WAMID = "wamid.HBgLOTE5ODc2NTQzMjEwFQIAERgSQzVGMkI3QTk5QTZEMkI0NTM5AA==";

    private HttpServer server;
    private final AtomicReference<Answer> answer = new AtomicReference<>();
    private final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    private final CountDownLatch never = new CountDownLatch(1);

    /** What the stand-in answers: status, body, extra headers, or never (to force a client timeout). */
    private record Answer(int status, String body, Map<String, String> headers, boolean hang) {}

    @BeforeEach
    void startStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.add(Map.of(
                    "method", exchange.getRequestMethod(),
                    "path", exchange.getRequestURI().getRawPath(),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Answer next = answer.get();
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
    void aTextIsOnePostToTheVersionedNumberWithTheBusinessToken() {
        answer(200, accepted(WAMID, null));

        WhatsAppSender.WhatsAppSendResult result = send(new WhatsAppSender.Text(TEXT));

        assertThat(result).isEqualTo(new WhatsAppSender.Accepted(WAMID));
        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst())
                .containsEntry("method", "POST")
                .containsEntry("path", "/v26.0/" + PHONE_NUMBER_ID + "/messages")
                .containsEntry("authorization", "Bearer " + TOKEN)
                .containsEntry("contentType", "application/json");
        assertThat(seen.getFirst().get("body"))
                .isEqualTo("{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\",\"to\":\"" + TO
                        + "\",\"type\":\"text\",\"text\":{\"body\":\"" + TEXT + "\"}}");
    }

    @Test
    void theVersionIsTheConfiguredOne() {
        answer(200, accepted(WAMID, null));

        sender("v27.0", Duration.ofSeconds(5)).send(outgoing(new WhatsAppSender.Text(TEXT)), token());

        assertThat(seen.getFirst()).containsEntry("path", "/v27.0/" + PHONE_NUMBER_ID + "/messages");
    }

    @Test
    void aTemplateCarriesItsValuesPositionalOrNamedAndNoComponentsWithoutThem() {
        String prefix = "{\"messaging_product\":\"whatsapp\",\"recipient_type\":\"individual\",\"to\":\"" + TO
                + "\",\"type\":\"template\",\"template\":{\"name\":\"booking_confirmed\",\"language\":{\"code\":\"en_US\"}";
        answer(200, accepted(WAMID, null));

        send(template(TemplateParameters.positional("Asha", "Saturday 10 am")));
        send(template(TemplateParameters.named(Map.of("first_name", "Asha"))));
        send(template(TemplateParameters.none()));

        assertThat(seen)
                .extracting(request -> request.get("body"))
                .containsExactly(
                        prefix + ",\"components\":[{\"type\":\"body\",\"parameters\":["
                                + "{\"type\":\"text\",\"text\":\"Asha\"},{\"type\":\"text\",\"text\":\"Saturday 10 am\"}]}]}}",
                        prefix + ",\"components\":[{\"type\":\"body\",\"parameters\":["
                                + "{\"type\":\"text\",\"parameter_name\":\"first_name\",\"text\":\"Asha\"}]}]}}",
                        prefix + "}}");
    }

    @Test
    void anAnswerWithAMessageIdIsAcceptedWhateverItsMessageStatus(CapturedOutput output) {
        for (String status :
                new String[] {null, "accepted", "held_for_quality_assessment", "paused", "something_new"}) {
            answer(200, accepted(WAMID, status));
            assertThat(send(new WhatsAppSender.Text(TEXT)))
                    .as(String.valueOf(status))
                    .isEqualTo(new WhatsAppSender.Accepted(WAMID));
        }
        assertThat(output.getAll())
                .contains("message_status=none")
                .contains("message_status=held_for_quality_assessment")
                .contains("message_status=other");
    }

    @Test
    void aSuccessWithoutAMessageIdIsPermanentSoItIsNeverRetried() {
        for (String body :
                List.of("{}", "{\"messages\": []}", "{\"messages\": [{\"id\": 42}]}", "<html>ok</html>", "")) {
            answer(200, body);
            assertThat(send(new WhatsAppSender.Text(TEXT)))
                    .as(body)
                    .isEqualTo(rejected(FailureClass.PERMANENT, "response_unreadable", null));
        }
    }

    @Test
    void failuresAreClassifiedByMetasCodeThenByTheStatus() {
        answer(400, error(131026, "Message undeliverable"));
        assertThat(sendText()).isEqualTo(rejected(FailureClass.PERMANENT, "recipient_unreachable", null));

        answer(401, error(190, "Error validating access token"));
        assertThat(sendText()).isEqualTo(rejected(FailureClass.AUTH, "token_expired", null));

        answer(403, error(200, "Permissions error"));
        assertThat(sendText()).isEqualTo(rejected(FailureClass.AUTH, "permission_missing", null));

        answer(400, error(130429, "Rate limit hit"), Map.of("Retry-After", "30"));
        assertThat(sendText())
                .isEqualTo(rejected(FailureClass.RETRYABLE, "rate_limited_throughput", Duration.ofSeconds(30)));

        answer(400, error(131056, "Pair rate limit hit"));
        assertThat(sendText()).isEqualTo(rejected(FailureClass.RETRYABLE, "rate_limited_pair", null));

        answer(400, error(132015, "Template is paused"));
        assertThat(sendText()).isEqualTo(rejected(FailureClass.BLOCKED, "template_paused", null));

        answer(400, error(2494100, "Account is in maintenance mode."));
        assertThat(sendText())
                .as("a code Meta lists for another endpoint is kept in ours")
                .isEqualTo(rejected(FailureClass.PERMANENT, "meta_2494100", null));

        answer(500, error(1, "An unknown error occurred"));
        assertThat(sendText()).isEqualTo(rejected(FailureClass.RETRYABLE, "meta_request_or_server_error", null));

        answer(502, "<html>Bad Gateway</html>");
        assertThat(sendText()).isEqualTo(rejected(FailureClass.RETRYABLE, "http_502", null));

        answer(429, "", Map.of("Retry-After", "7"));
        assertThat(sendText()).isEqualTo(rejected(FailureClass.RETRYABLE, "http_429", Duration.ofSeconds(7)));

        answer(401, "");
        assertThat(sendText()).isEqualTo(rejected(FailureClass.AUTH, "http_401", null));

        answer(404, "{\"error\": {\"message\": \"Unknown path\"}}");
        assertThat(sendText()).isEqualTo(rejected(FailureClass.PERMANENT, "http_404", null));
    }

    @Test
    void timeoutsAndLostConnectionsAreRetryable() throws IOException {
        answer.set(new Answer(0, "", Map.of(), true));
        assertThat(sender("v26.0", Duration.ofMillis(300)).send(outgoing(new WhatsAppSender.Text(TEXT)), token()))
                .isEqualTo(rejected(FailureClass.RETRYABLE, "timeout", null));

        HttpServer closed = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = closed.getAddress().getPort();
        closed.start();
        closed.stop(0);
        CloudApiWhatsAppSender nobodyThere = new CloudApiWhatsAppSender(
                http(), URI.create("http://127.0.0.1:" + port), "v26.0", Duration.ofSeconds(2));
        assertThat(nobodyThere.send(outgoing(new WhatsAppSender.Text(TEXT)), token()))
                .isEqualTo(rejected(FailureClass.RETRYABLE, "connection_error", null));
    }

    @Test
    void aRedirectIsNeverFollowedSoTheTokenGoesNowhereElse() {
        answer(302, "", Map.of("Location", base() + "/v26.0/elsewhere/messages"));

        assertThat(sendText()).isEqualTo(rejected(FailureClass.PERMANENT, "http_302", null));
        assertThat(seen).as("one request, to the configured path").hasSize(1);
    }

    @Test
    void aPhoneNumberIdThatIsNotDigitsSendsNothing() {
        answer(200, accepted(WAMID, null));
        for (String id : List.of("pn-123", "123/../456", "123?x=1", "", "١٢٣")) {
            WhatsAppSender.OutgoingWhatsApp message =
                    new WhatsAppSender.OutgoingWhatsApp(UUID.randomUUID(), id, TO, new WhatsAppSender.Text(TEXT));
            assertThat(sender("v26.0", Duration.ofSeconds(5)).send(message, token()))
                    .as(id)
                    .isEqualTo(rejected(FailureClass.PERMANENT, "phone_number_id_invalid", null));
        }
        assertThat(seen).isEmpty();
    }

    @Test
    void logsCarryIdsStatusesAndCodesOnly(CapturedOutput output) {
        UUID messageId = UUID.randomUUID();
        String details = "Message to " + TO + " failed: " + TEXT;
        answer(
                400,
                "{\"error\": {\"message\": \"(#131026) " + details
                        + "\", \"type\": \"OAuthException\", \"code\": 131026,"
                        + " \"error_data\": {\"messaging_product\": \"whatsapp\", \"details\": \"" + details + "\"},"
                        + " \"fbtrace_id\": \"AbC-123_xyz\"}}");
        sender("v26.0", Duration.ofSeconds(5))
                .send(
                        new WhatsAppSender.OutgoingWhatsApp(
                                messageId, PHONE_NUMBER_ID, TO, new WhatsAppSender.Text(TEXT)),
                        token());
        answer(200, accepted(WAMID, "accepted"));
        sendText();

        assertThat(output.getAll())
                .contains(
                        "whatsapp.cloud_api.failed message_id=" + messageId
                                + " status=400 meta_code=131026 class=permanent code=recipient_unreachable fbtrace_id=AbC-123_xyz")
                .contains("whatsapp.cloud_api.accepted")
                .doesNotContain(TO)
                .doesNotContain(TO.substring(1))
                .doesNotContain(TEXT)
                .doesNotContain(TOKEN)
                .doesNotContain(WAMID)
                .doesNotContain(PHONE_NUMBER_ID)
                .doesNotContain("OAuthException");
    }

    // Helpers.

    private WhatsAppSender.WhatsAppSendResult send(WhatsAppSender.Body body) {
        return sender("v26.0", Duration.ofSeconds(5)).send(outgoing(body), token());
    }

    private WhatsAppSender.WhatsAppSendResult sendText() {
        return send(new WhatsAppSender.Text(TEXT));
    }

    private CloudApiWhatsAppSender sender(String version, Duration requestTimeout) {
        return new CloudApiWhatsAppSender(http(), base(), version, requestTimeout);
    }

    private static WhatsAppSender.OutgoingWhatsApp outgoing(WhatsAppSender.Body body) {
        return new WhatsAppSender.OutgoingWhatsApp(UUID.randomUUID(), PHONE_NUMBER_ID, TO, body);
    }

    private static WhatsAppSender.Template template(TemplateParameters parameters) {
        return new WhatsAppSender.Template("booking_confirmed", "en_US", parameters);
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

    private void answer(int status, String body) {
        answer(status, body, Map.of());
    }

    private void answer(int status, String body, Map<String, String> headers) {
        answer.set(new Answer(status, body, headers, false));
    }

    /** Meta's success answer; messageStatus null leaves the field out. */
    private static String accepted(String wamid, String messageStatus) {
        String status = messageStatus == null ? "" : ", \"message_status\": \"" + messageStatus + "\"";
        return "{\"messaging_product\": \"whatsapp\", \"contacts\": [{\"input\": \"" + TO + "\", \"wa_id\": \""
                + TO.substring(1) + "\"}], \"messages\": [{\"id\": \"" + wamid + "\"" + status + "}]}";
    }

    /** Meta's error answer, with a message that echoes the request, as Meta's can. */
    private static String error(int code, String title) {
        return "{\"error\": {\"message\": \"(#" + code + ") " + title + "\", \"type\": \"OAuthException\", \"code\": "
                + code + ", \"error_data\": {\"messaging_product\": \"whatsapp\", \"details\": \"" + TEXT + "\"},"
                + " \"fbtrace_id\": \"Az8or2yhqkZfEZ-_4Qn_Bam\"}}";
    }

    private static WhatsAppSender.Rejected rejected(FailureClass failureClass, String code, Duration retryAfter) {
        return new WhatsAppSender.Rejected(new ActionOutcome.Failed(failureClass, code, retryAfter));
    }
}
