package com.northstar.integrations.internal;

import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.FailureClass;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Reads a business's WhatsApp account from Meta's Graph API (integration-specs.md section 2) with the business's token as
 * a bearer token, on Java's own HttpClient, with the same base URL, pinned version, timeouts and no redirects as the
 * send adapter ({@link CloudApiWhatsAppSender}):
 *
 * <ul>
 *   <li>a number's quality rating and messaging limit (step 2.8a, Quality and limits): {@code GET
 *       /{version}/{phone-number-id}?fields=quality_rating,whatsapp_business_manager_messaging_limit};
 *   <li>a page of the account's templates (step 2.8b, Template status from Meta): {@code GET
 *       /{version}/{waba-id}/message_templates?fields=id,name,language,status,category,parameter_format,components
 *       &limit=100}, with {@code &after=<cursor>} for each next page. The URL is always built here from the configured
 *       base and Meta's cursor; Meta's {@code paging.next} URL is never followed, so the token goes nowhere else. A page
 *       is the last when it has no {@code paging.next}; an entry without a usable ID is counted as unreadable.
 * </ul>
 *
 * <p>Meta shows {@code whatsapp_business_manager_messaging_limit} on v27.0 and does not say when it was added; the
 * pinned version is v26.0. The Graph API answers a field a version does not have with error 100 rather than leaving it
 * out, so a 100 on the full read is followed by one read of {@code quality_rating} alone: if that succeeds, the number
 * is returned without a limit ({@code limitFieldRefused}) and the sync keeps the stored one. Any other failure is
 * classified by {@link MetaErrors}, as for sends. A 2xx that is not a JSON object is retryable
 * {@value #RESPONSE_UNREADABLE}, since a read changes nothing.
 *
 * <p>Logs carry Northstar's account ID, the HTTP status, Meta's numeric code, the class, our code, whether the limit
 * field came back, template counts, and Meta's trace ID; never the token, phone number or WABA IDs, cursors, Meta's
 * values, template names or text, or Meta's error message.
 */
final class CloudApiWhatsAppAccountReader implements WhatsAppAccountReader {

    private static final Logger log = LoggerFactory.getLogger(CloudApiWhatsAppAccountReader.class);

    static final String QUALITY_FIELD = "quality_rating";
    static final String LIMIT_FIELD = "whatsapp_business_manager_messaging_limit";
    static final String RESPONSE_UNREADABLE = "response_unreadable";

    /** Graph's code for a field this version does not have (and for any other invalid parameter). */
    static final int INVALID_PARAMETER = 100;

    static final String TEMPLATE_FIELDS = "id,name,language,status,category,parameter_format,components";

    /** Templates per page. */
    static final int PAGE_SIZE = 100;

    /** Meta's phone number IDs are digits; anything else would change the request path. */
    private static final Pattern PHONE_NUMBER_ID = Pattern.compile("\\d{1,64}");

    /** Meta's WABA IDs are digits too. */
    private static final Pattern WABA_ID = Pattern.compile("\\d{1,64}");

    /** Meta's cursors are opaque base64-like text; anything else is not one. */
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_=+/-]{1,1024}");

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
        Answer full = get(
                "/" + version + "/" + phoneNumberId + "?fields=" + QUALITY_FIELD + "," + LIMIT_FIELD, businessToken);
        if (full instanceof TransportFailure failed) return transportFailed(accountId, failed.failure());
        HttpResponse<String> response = ((Response) full).response();
        if (ok(response)) return health(accountId, response, false);

        JsonNode error = GraphAnswers.error(GraphAnswers.parse(response.body()));
        Integer metaCode = GraphAnswers.metaCode(error);
        if (metaCode != null && metaCode == INVALID_PARAMETER) {
            Answer qualityOnly = get("/" + version + "/" + phoneNumberId + "?fields=" + QUALITY_FIELD, businessToken);
            if (qualityOnly instanceof TransportFailure failed) return transportFailed(accountId, failed.failure());
            HttpResponse<String> narrower = ((Response) qualityOnly).response();
            if (ok(narrower)) return health(accountId, narrower, true);
            return failed(accountId, narrower);
        }
        return failed(accountId, response);
    }

    @Override
    public TemplatePageRead listTemplates(String wabaId, String after, Secret businessToken) {
        if (wabaId == null || !WABA_ID.matcher(wabaId).matches()) {
            return templatesFailed(new ActionOutcome.Failed(FailureClass.PERMANENT, "waba_id_invalid", null));
        }
        if (after != null && !CURSOR.matcher(after).matches()) {
            return templatesFailed(new ActionOutcome.Failed(FailureClass.PERMANENT, "cursor_invalid", null));
        }
        String query = "fields=" + TEMPLATE_FIELDS + "&limit=" + PAGE_SIZE
                + (after == null ? "" : "&after=" + URLEncoder.encode(after, StandardCharsets.UTF_8));
        Answer answer = get("/" + version + "/" + wabaId + "/message_templates?" + query, businessToken);
        if (answer instanceof TransportFailure failed) return templatesFailed(failed.failure());
        HttpResponse<String> response = ((Response) answer).response();
        if (!ok(response)) {
            JsonNode error = GraphAnswers.error(GraphAnswers.parse(response.body()));
            Integer metaCode = GraphAnswers.metaCode(error);
            ActionOutcome.Failed failure = MetaErrors.classify(
                    response.statusCode(),
                    metaCode,
                    GraphAnswers.retryAfter(response).orElse(null));
            log.warn(
                    "whatsapp.cloud_api.templates_failed status={} meta_code={} class={} code={} fbtrace_id={}",
                    response.statusCode(),
                    metaCode == null ? "none" : metaCode,
                    failure.failureClass().databaseValue(),
                    failure.code(),
                    GraphAnswers.traceId(error));
            return new Failed(failure);
        }
        return page(response);
    }

    /** A page of templates, or retryable {@value #RESPONSE_UNREADABLE} when the answer is not one. */
    private static TemplatePageRead page(HttpResponse<String> response) {
        JsonNode json = GraphAnswers.parse(response.body());
        JsonNode data = json == null || !json.isObject() ? null : json.get("data");
        if (data == null || !data.isArray()) return unreadablePage(response);
        List<ListedTemplate> templates = new ArrayList<>();
        int unreadable = 0;
        for (JsonNode entry : data.values()) {
            Optional<String> id =
                    entry.isObject() ? WhatsAppTemplateUpdate.templateId(entry.get("id")) : Optional.empty();
            if (id.isEmpty()) {
                unreadable++;
                continue;
            }
            templates.add(new ListedTemplate(
                    id.get(),
                    cleanText(entry.get("name")),
                    cleanText(entry.get("language")),
                    text(entry.get("status")),
                    text(entry.get("category")),
                    text(entry.get("parameter_format")),
                    body(entry.get("components"))));
        }
        JsonNode paging = json.path("paging");
        String after = null;
        JsonNode next = paging.get("next");
        if (next != null && !next.isNull()) {
            after = text(paging.path("cursors").get("after"));
            if (after == null || !CURSOR.matcher(after).matches()) return unreadablePage(response);
        }
        log.info(
                "whatsapp.cloud_api.templates_read status={} templates={} unreadable={} last_page={}",
                response.statusCode(),
                templates.size(),
                unreadable,
                after == null);
        return new TemplatePage(templates, unreadable, after);
    }

    /** The BODY component's text, as sent (null if there is none). */
    private static String body(JsonNode components) {
        if (components == null || !components.isArray()) return null;
        for (JsonNode component : components.values()) {
            String type = text(component.get("type"));
            if (type != null && type.equalsIgnoreCase("BODY")) return cleanText(component.get("text"));
        }
        return null;
    }

    /** Text Postgres can hold: U+0000 and lone surrogates become U+FFFD, as in webhook slices. */
    private static String cleanText(JsonNode node) {
        String value = text(node);
        return value == null ? null : WhatsAppDelivery.clean(value);
    }

    private static TemplatePageRead unreadablePage(HttpResponse<String> response) {
        log.warn(
                "whatsapp.cloud_api.templates_failed status={} class=retryable code={}",
                response.statusCode(),
                RESPONSE_UNREADABLE);
        return new Failed(new ActionOutcome.Failed(FailureClass.RETRYABLE, RESPONSE_UNREADABLE, null));
    }

    private static TemplatePageRead templatesFailed(ActionOutcome.Failed failure) {
        log.warn(
                "whatsapp.cloud_api.templates_failed class={} code={}",
                failure.failureClass().databaseValue(),
                failure.code());
        return new Failed(failure);
    }

    private Answer get(String pathAndQuery, Secret businessToken) {
        HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve(pathAndQuery))
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
