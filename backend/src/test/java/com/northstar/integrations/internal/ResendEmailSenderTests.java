package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.integrations.OutgoingEmail;
import com.northstar.workflow.ActionOutcome;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The real Resend adapter against a stand-in Resend on localhost (the network guard blocks everything else): what it
 * sends, and how each documented answer is classified (integration-specs.md sections 1 and 6).
 */
@ExtendWith(OutputCaptureExtension.class)
class ResendEmailSenderTests {

    private static final OutgoingEmail EMAIL =
            new OutgoingEmail("asha.rao@example.com", "Test business invited you to Northstar", "Hi Asha, the link");

    private HttpServer server;
    private final AtomicReference<Answer> answer = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> seen = new AtomicReference<>();
    private final CountDownLatch never = new CountDownLatch(1);

    /** What the stand-in answers: status, JSON body, extra headers, or never (to force a timeout). */
    private record Answer(int status, String body, Map<String, String> headers, boolean hang) {}

    @BeforeEach
    void startStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.set(Map.of(
                    "method", exchange.getRequestMethod(),
                    "path", exchange.getRequestURI().getPath(),
                    "authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")),
                    "idempotencyKey",
                            String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
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
            exchange.sendResponseHeaders(next.status(), body.length);
            exchange.getResponseBody().write(body);
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
    void aSendIsOnePostWithTheKeyAndTheIdempotencyKey() {
        answer(200, "{\"id\": \"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}");

        ActionOutcome outcome = sender().send(EMAIL, "invite-email:42");

        assertThat(outcome)
                .isEqualTo(ActionOutcome.succeeded("{\"emailId\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}"));
        assertThat(seen.get())
                .containsEntry("method", "POST")
                .containsEntry("path", "/emails")
                .containsEntry("authorization", "Bearer re_test_key")
                .containsEntry("idempotencyKey", "invite-email:42")
                .containsEntry("contentType", "application/json");
        assertThat(seen.get().get("body"))
                .isEqualTo("{\"from\":\"Northstar <invites@example.test>\",\"to\":[\"asha.rao@example.com\"],"
                        + "\"subject\":\"Test business invited you to Northstar\",\"text\":\"Hi Asha, the link\"}");
    }

    @Test
    void rateLimitsAreRetriedAfterResendsRetryAfter() {
        answer(429, error(429, "rate_limit_exceeded"), Map.of("retry-after", "7"));
        assertThat(send()).isEqualTo(ActionOutcome.retryableAfter("http_429", Duration.ofSeconds(7)));

        answer(429, error(429, "rate_limit_exceeded"), Map.of());
        assertThat(send()).isEqualTo(ActionOutcome.retryableAfter("http_429", null));
    }

    @Test
    void exhaustedQuotasArePermanent() {
        answer(429, error(429, "daily_quota_exceeded"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("quota_exceeded"));
        answer(429, error(429, "monthly_quota_exceeded"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("quota_exceeded"));
    }

    @Test
    void serverErrorsAndTimeoutsAreRetryable() {
        answer(500, error(500, "application_error"));
        assertThat(send()).isEqualTo(ActionOutcome.retryable("http_500"));
        answer(503, "<html>unavailable</html>");
        assertThat(send()).isEqualTo(ActionOutcome.retryable("http_503"));

        answer.set(new Answer(0, "", Map.of(), true));
        assertThat(new ResendEmailSender(
                                http(),
                                base(),
                                "re_test_key",
                                "Northstar <invites@example.test>",
                                Duration.ofMillis(300))
                        .send(EMAIL, "k"))
                .isEqualTo(ActionOutcome.retryable("timeout"));
    }

    @Test
    void aRejectedPlatformKeyIsPermanentAndLoggedForThePlatform(CapturedOutput output) {
        answer(401, error(401, "validation_error"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("auth_rejected"));
        answer(403, error(403, "suspended_api_key"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("auth_rejected"));
        answer(401, error(401, "missing_api_key"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("auth_rejected"));

        assertThat(output.getAll()).contains("ERROR").contains("resend_key_rejected status=401");
    }

    @Test
    void invalidRequestsAndSenderProblemsArePermanent() {
        answer(422, error(422, "missing_required_field"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("invalid_request"));
        answer(400, error(400, "validation_error"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("invalid_request"));
        answer(403, error(403, "validation_error"));
        assertThat(send())
                .as("unverified domain or testing mode")
                .isEqualTo(ActionOutcome.permanent("sender_not_allowed"));
    }

    @Test
    void idempotencyAnswersFollowResendsRules() {
        answer(409, error(409, "concurrent_idempotent_requests"));
        assertThat(send()).isEqualTo(ActionOutcome.retryable("idempotency_in_progress"));
        answer(409, error(409, "invalid_idempotent_request"));
        assertThat(send()).isEqualTo(ActionOutcome.permanent("idempotency_conflict"));
    }

    @Test
    void logsCarryStatusAndErrorNamesOnly(CapturedOutput output) {
        answer(
                422,
                "{\"statusCode\": 422, \"name\": \"missing_required_field\","
                        + " \"message\": \"asha.rao@example.com is missing a subject\"}");
        send();
        answer(200, "{\"id\": \"e-1\"}");
        send();

        assertThat(output.getAll())
                .contains("resend.failed status=422 error=missing_required_field")
                .doesNotContain("asha.rao@example.com", "Hi Asha", "re_test_key", "is missing a subject");
    }

    private ActionOutcome send() {
        return sender().send(EMAIL, "invite-email:k");
    }

    private ResendEmailSender sender() {
        return new ResendEmailSender(
                http(), base(), "re_test_key", "Northstar <invites@example.test>", Duration.ofSeconds(5));
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private void answer(int status, String body) {
        answer(status, body, Map.of());
    }

    private void answer(int status, String body, Map<String, String> headers) {
        answer.set(new Answer(status, body, headers, false));
    }

    private static String error(int status, String name) {
        return "{\"statusCode\": " + status + ", \"name\": \"" + name + "\", \"message\": \"details\"}";
    }
}
