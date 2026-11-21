package com.northstar.integrations.internal;

import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.FailureClass;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Reads a WhatsApp number's quality rating and messaging limit from Meta's Graph API (step 2.8a, integration-specs.md
 * section 2, Quality and limits): {@code GET /{version}/{phone-number-id}?fields=quality_rating,
 * whatsapp_business_manager_messaging_limit} with the business's token as a bearer token, on Java's own HttpClient,
 * with the same base URL, pinned version, timeouts and no redirects as the send adapter ({@link CloudApiWhatsAppSender}).
 *
 * <p>Meta shows {@code whatsapp_business_manager_messaging_limit} on v27.0 and does not say when it was added; the
 * pinned version is v26.0. The Graph API answers a field a version does not have with error 100 rather than leaving it
 * out, so a 100 on the full read is followed by one read of {@code quality_rating} alone: if that succeeds, the number
 * is returned without a limit ({@code limitFieldRefused}) and the sync keeps the stored one. Any other failure is
 * classified by {@link MetaErrors}, as for sends. A 2xx that is not a JSON object is retryable
 * {@value #RESPONSE_UNREADABLE}, since a read changes nothing.
 *
 * <p>Logs carry Northstar's account ID, the HTTP status, Meta's numeric code, the class, our code, whether the limit
 * field came back, and Meta's trace ID; never the token, the phone number ID, Meta's values or Meta's error message.
 */
final class CloudApiWhatsAppAccountReader implements WhatsAppAccountReader {

    private static final Logger log = LoggerFactory.getLogger(CloudApiWhatsAppAccountReader.class);

    static final String QUALITY_FIELD = "quality_rating";
    static final String LIMIT_FIELD = "whatsapp_business_manager_messaging_limit";
    static final String RESPONSE_UNREADABLE = "response_unreadable";

    /** Graph's code for a field this version does not have (and for any other invalid parameter). */
    static final int INVALID_PARAMETER = 100;

    /** Meta's phone number IDs are digits; anything else would change the request path. */
    private static final Pattern PHONE_NUMBER_ID = Pattern.compile("\\d{1,64}");

    private final HttpClient http;
    private final URI baseUrl;
    private final String version;
    private final Duration requestTimeout;

    CloudApiWhatsAppAccountReader(HttpClient http, URI baseUrl, String version, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.version = Objects.requireNonNull(version, "version");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    /** One request's answer: an HTTP response, or a transport failure already classified. */
    private sealed interface Answer {}

    private record Response(HttpResponse<String> response) implements Answer {}

    private record TransportFailure(ActionOutcome.Failed failure) implements Answer {}

    @Override
    public NumberRead readNumber(UUID accountId, String phoneNumberId, Secret businessToken) {
        if (phoneNumberId == null || !PHONE_NUMBER_ID.matcher(phoneNumberId).matches()) {
            return transportFailed(
                    accountId, new ActionOutcome.Failed(FailureClass.PERMANENT, "phone_number_id_invalid", null));
        }
        Answer full = get(phoneNumberId, QUALITY_FIELD + "," + LIMIT_FIELD, businessToken);
        if (full instanceof TransportFailure failed) return transportFailed(accountId, failed.failure());
        HttpResponse<String> response = ((Response) full).response();
        if (ok(response)) return health(accountId, response, false);

        JsonNode error = GraphAnswers.error(GraphAnswers.parse(response.body()));
        Integer metaCode = GraphAnswers.metaCode(error);
        if (metaCode != null && metaCode == INVALID_PARAMETER) {
            Answer qualityOnly = get(phoneNumberId, QUALITY_FIELD, businessToken);
            if (qualityOnly instanceof TransportFailure failed) return transportFailed(accountId, failed.failure());
            HttpResponse<String> narrower = ((Response) qualityOnly).response();
            if (ok(narrower)) return health(accountId, narrower, true);
            return failed(accountId, narrower);
        }
        return failed(accountId, response);
    }

    private Answer get(String phoneNumberId, String fields, Secret businessToken) {
        HttpRequest request = HttpRequest.newBuilder(
                        baseUrl.resolve("/" + version + "/" + phoneNumberId + "?fields=" + fields))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + businessToken.utf8())
                .GET()
                .build();
        try {
            return new Response(http.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (HttpTimeoutException e) {
            return new TransportFailure(new ActionOutcome.Failed(FailureClass.RETRYABLE, "timeout", null));
        } catch (IOException e) {
            return new TransportFailure(new ActionOutcome.Failed(FailureClass.RETRYABLE, "connection_error", null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TransportFailure(new ActionOutcome.Failed(FailureClass.RETRYABLE, "interrupted", null));
        }
    }

    private static boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static NumberRead health(UUID accountId, HttpResponse<String> response, boolean limitFieldRefused) {
        JsonNode json = GraphAnswers.parse(response.body());
        if (json == null || !json.isObject()) {
            log.warn(
                    "whatsapp.cloud_api.read_failed account_id={} status={} class=retryable code={}",
                    accountId,
                    response.statusCode(),
                    RESPONSE_UNREADABLE);
            return new Failed(new ActionOutcome.Failed(FailureClass.RETRYABLE, RESPONSE_UNREADABLE, null));
        }
        String quality = text(json.get(QUALITY_FIELD));
        String limit = limitFieldRefused ? null : text(json.get(LIMIT_FIELD));
        log.info(
                "whatsapp.cloud_api.number_read account_id={} status={} limit_field={}",
                accountId,
                response.statusCode(),
                limitFieldRefused ? "refused" : limit == null ? "absent" : "present");
        return new NumberHealth(quality, limit, limitFieldRefused);
    }

    private static NumberRead failed(UUID accountId, HttpResponse<String> response) {
        JsonNode error = GraphAnswers.error(GraphAnswers.parse(response.body()));
        Integer metaCode = GraphAnswers.metaCode(error);
        ActionOutcome.Failed failure = MetaErrors.classify(
                response.statusCode(),
                metaCode,
                GraphAnswers.retryAfter(response).orElse(null));
        log.warn(
                "whatsapp.cloud_api.read_failed account_id={} status={} meta_code={} class={} code={} fbtrace_id={}",
                accountId,
                response.statusCode(),
                metaCode == null ? "none" : metaCode,
                failure.failureClass().databaseValue(),
                failure.code(),
                GraphAnswers.traceId(error));
        return new Failed(failure);
    }

    private static NumberRead transportFailed(UUID accountId, ActionOutcome.Failed failure) {
        log.warn(
                "whatsapp.cloud_api.read_failed account_id={} class={} code={}",
                accountId,
                failure.failureClass().databaseValue(),
                failure.code());
        return new Failed(failure);
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
