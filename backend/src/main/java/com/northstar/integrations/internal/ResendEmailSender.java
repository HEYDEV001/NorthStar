package com.northstar.integrations.internal;

import com.northstar.integrations.EmailSender;
import com.northstar.integrations.OutgoingEmail;
import com.northstar.workflow.ActionOutcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Sends email through Resend's API (POST /emails) with Java's own HttpClient (integration-specs.md section 6).
 *
 * <p>The action's idempotency key goes in Resend's Idempotency-Key header. Resend keeps it 24 hours: the same key with
 * the same body is not sent again and returns the first response; the same key with a different body is refused (409
 * invalid_idempotent_request); the same key while the first request is still running is a 409
 * concurrent_idempotent_requests.
 *
 * <p>Answers are classified as integration-specs.md section 1 says: 429 rate limits, 5xx, timeouts, lost connections
 * and a concurrent idempotent request are retryable (429 with its Retry-After); a rejected or suspended platform key,
 * an unverified sender, quota exhaustion, invalid requests and an idempotency conflict are permanent. A rejected key
 * affects every tenant, so it also logs the platform event resend_key_rejected at ERROR. Logs carry the HTTP status and
 * Resend's error name only, never an address, subject, body, key or Resend's message (which can echo input).
 */
final class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private static final Set<String> KEY_ERRORS =
            Set.of("missing_api_key", "restricted_api_key", "suspended_api_key", "invalid_permission");
    private static final Set<String> QUOTA_ERRORS = Set.of("daily_quota_exceeded", "monthly_quota_exceeded");

    private final HttpClient http;
    private final URI emailsEndpoint;
    private final String apiKey;
    private final String from;
    private final Duration requestTimeout;

    ResendEmailSender(HttpClient http, URI baseUrl, String apiKey, String from, Duration requestTimeout) {
        this.http = http;
        this.emailsEndpoint = baseUrl.resolve("/emails");
        this.apiKey = apiKey;
        this.from = from;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public ActionOutcome send(OutgoingEmail email, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(email.to()));
        body.put("subject", email.subject());
        body.put("text", email.text());
        HttpRequest request = HttpRequest.newBuilder(emailsEndpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            log.warn("resend.failed error=timeout");
            return ActionOutcome.retryable("timeout");
        } catch (IOException e) {
            log.warn("resend.failed error=connection_error");
            return ActionOutcome.retryable("connection_error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ActionOutcome.retryable("interrupted");
        }
        return classify(response);
    }

    private static ActionOutcome classify(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            String emailId = field(response.body(), "id").orElse(null);
            log.info("resend.sent status={}", status);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("emailId", emailId);
            return ActionOutcome.succeeded(ProviderJson.MAPPER.writeValueAsString(result));
        }
        String error = field(response.body(), "name")
                .filter(name -> name.matches("[a-z_]{1,64}"))
                .orElse("unknown");
        log.warn("resend.failed status={} error={}", status, error);

        if (status == 401 || (status == 403 && KEY_ERRORS.contains(error))) {
            log.error("resend_key_rejected status={} error={}", status, error);
            return ActionOutcome.permanent("auth_rejected");
        }
        if (status == 429) {
            if (QUOTA_ERRORS.contains(error)) return ActionOutcome.permanent("quota_exceeded");
            return ActionOutcome.retryableAfter("http_429", retryAfter(response).orElse(null));
        }
        if (status == 409) {
            if ("concurrent_idempotent_requests".equals(error))
                return ActionOutcome.retryable("idempotency_in_progress");
            if ("invalid_idempotent_request".equals(error)) return ActionOutcome.permanent("idempotency_conflict");
            return ActionOutcome.retryable("http_409");
        }
        if (status == 403) return ActionOutcome.permanent("sender_not_allowed");
        if (status == 400 || status == 422) return ActionOutcome.permanent("invalid_request");
        if (status >= 500) return ActionOutcome.retryable("http_" + status);
        return ActionOutcome.permanent("http_" + status);
    }

    /** Resend's retry-after header, in seconds. */
    private static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers()
                .firstValue("retry-after")
                .filter(value -> value.matches("\\d{1,6}"))
                .map(value -> Duration.ofSeconds(Long.parseLong(value)));
    }

    private static Optional<String> field(String body, String name) {
        try {
            JsonNode node =
                    ProviderJson.MAPPER.readTree(body == null ? "" : body).get(name);
            return node != null && node.isString() ? Optional.of(node.asString()) : Optional.empty();
        } catch (RuntimeException notJson) {
            return Optional.empty();
        }
    }
}
