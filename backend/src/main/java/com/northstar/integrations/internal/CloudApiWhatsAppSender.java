package com.northstar.integrations.internal;

import com.northstar.contacts.TemplateParameters;
import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.FailureClass;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Sends WhatsApp messages through Meta's Cloud API (step 2.6b, integration-specs.md section 2):
 * {@code POST /{version}/{phone-number-id}/messages} on the Graph API, with Java's own HttpClient (no SDK) and the
 * business's token as a bearer token. The version is the one pinned in configuration
 * ({@link WhatsAppCloudApiConfiguration}).
 *
 * <p>Meta takes no idempotency key, so each call is exactly one request and redirects are never followed (the token
 * goes to the configured host only). The HTTP client gives up well before the send handler's timeout, so an answer that
 * arrives is always returned and stored, never cut off and retried as a duplicate.
 *
 * <p>An answer with a message ID is accepted, whatever its {@code message_status}. A success without one is permanent
 * {@code response_unreadable}: Meta may have sent the message, so it is never retried. Failures are classified by
 * {@link MetaErrors}, on Meta's error code first. Logs carry Northstar's message ID, the HTTP status, Meta's numeric
 * code, the class and fixed code, and Meta's trace ID; never the text, the numbers, the token, Meta's message ID or
 * Meta's error message and details (which can echo the request).
 */
final class CloudApiWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(CloudApiWhatsAppSender.class);

    /** Meta's phone number IDs are digits; anything else would change the request path. */
    private static final Pattern PHONE_NUMBER_ID = Pattern.compile("\\d{1,64}");

    /** The pacing statuses Meta's reference lists; any other value is logged as "other". */
    private static final Set<String> MESSAGE_STATUSES = Set.of("accepted", "held_for_quality_assessment", "paused");

    private final HttpClient http;
    private final URI baseUrl;
    private final String version;
    private final Duration requestTimeout;

    CloudApiWhatsAppSender(HttpClient http, URI baseUrl, String version, Duration requestTimeout) {
        this.http = Objects.requireNonNull(http, "http");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.version = Objects.requireNonNull(version, "version");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public WhatsAppSendResult send(OutgoingWhatsApp message, Secret businessToken) {
        UUID messageId = message.messageId();
        if (!PHONE_NUMBER_ID.matcher(message.phoneNumberId()).matches()) {
            return rejected(
                    messageId, new ActionOutcome.Failed(FailureClass.PERMANENT, "phone_number_id_invalid", null));
        }
        HttpRequest request = HttpRequest.newBuilder(
                        baseUrl.resolve("/" + version + "/" + message.phoneNumberId() + "/messages"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + businessToken.utf8())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ProviderJson.MAPPER.writeValueAsString(body(message))))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return rejected(messageId, new ActionOutcome.Failed(FailureClass.RETRYABLE, "timeout", null));
        } catch (IOException e) {
            return rejected(messageId, new ActionOutcome.Failed(FailureClass.RETRYABLE, "connection_error", null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return rejected(messageId, new ActionOutcome.Failed(FailureClass.RETRYABLE, "interrupted", null));
        }
        return answer(messageId, response);
    }

    /** The request body: Meta's text or template message, with Meta's field names. */
    static Map<String, Object> body(OutgoingWhatsApp message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", message.to());
        switch (message.body()) {
            case Text text -> {
                body.put("type", "text");
                body.put("text", Map.of("body", text.text()));
            }
            case Template template -> {
                body.put("type", "template");
                body.put("template", template(template));
            }
        }
        return body;
    }

    /** A template with no values sends no components (Meta's pages show components only to carry values). */
    private static Map<String, Object> template(Template template) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", template.name());
        fields.put("language", Map.of("code", template.language()));
        List<Map<String, Object>> parameters = switch (template.parameters()) {
            case TemplateParameters.Positional positional ->
                positional.values().stream()
                        .map(value -> parameter(null, value))
                        .toList();
            case TemplateParameters.Named named ->
                named.values().entrySet().stream()
                        .map(entry -> parameter(entry.getKey(), entry.getValue()))
                        .toList();
        };
        if (!parameters.isEmpty()) {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("type", "body");
            component.put("parameters", parameters);
            fields.put("components", List.of(component));
        }
        return fields;
    }

    private static Map<String, Object> parameter(String name, String value) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("type", "text");
        if (name != null) parameter.put("parameter_name", name);
        parameter.put("text", value);
        return parameter;
    }

    private WhatsAppSendResult answer(UUID messageId, HttpResponse<String> response) {
        int status = response.statusCode();
        JsonNode json = GraphAnswers.parse(response.body());
        if (status >= 200 && status < 300) {
            Optional<String> providerMessageId = providerMessageId(json);
            if (providerMessageId.isPresent()) {
                log.info(
                        "whatsapp.cloud_api.accepted message_id={} status={} message_status={}",
                        messageId,
                        status,
                        messageStatus(json));
                return new Accepted(providerMessageId.get());
            }
            log.error("whatsapp.cloud_api.unreadable message_id={} status={}", messageId, status);
            return new Rejected(new ActionOutcome.Failed(FailureClass.PERMANENT, "response_unreadable", null));
        }
        JsonNode error = GraphAnswers.error(json);
        Integer metaCode = GraphAnswers.metaCode(error);
        ActionOutcome.Failed failure = MetaErrors.classify(
                status, metaCode, GraphAnswers.retryAfter(response).orElse(null));
        log.warn(
                "whatsapp.cloud_api.failed message_id={} status={} meta_code={} class={} code={} fbtrace_id={}",
                messageId,
                status,
                metaCode == null ? "none" : metaCode,
                failure.failureClass().databaseValue(),
                failure.code(),
                GraphAnswers.traceId(error));
        return new Rejected(failure);
    }

    private static WhatsAppSendResult rejected(UUID messageId, ActionOutcome.Failed failure) {
        log.warn(
                "whatsapp.cloud_api.failed message_id={} class={} code={}",
                messageId,
                failure.failureClass().databaseValue(),
                failure.code());
        return new Rejected(failure);
    }

    /** messages[0].id, Meta's message ID (the wamid). */
    private static Optional<String> providerMessageId(JsonNode json) {
        JsonNode messages = json == null ? null : json.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) return Optional.empty();
        JsonNode id = messages.get(0).get("id");
        if (id == null || !id.isString()) return Optional.empty();
        String value = id.asString();
        return value.isBlank() || value.length() > 200 ? Optional.empty() : Optional.of(value);
    }

    private static String messageStatus(JsonNode json) {
        JsonNode status = json.get("messages").get(0).get("message_status");
        if (status == null || !status.isString()) return "none";
        return MESSAGE_STATUSES.contains(status.asString()) ? status.asString() : "other";
    }
}
