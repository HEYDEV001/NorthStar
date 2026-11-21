package com.northstar.integrations.internal;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads the parts of a Graph API answer that Northstar logs or classifies (steps 2.6b and 2.8a): Meta's numeric error
 * code, its support trace ID, and a Retry-After. Shared by the Cloud API adapters; never reads Meta's error message or
 * details, which can echo the request.
 */
final class GraphAnswers {

    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private GraphAnswers() {}

    /** The body as JSON, through the lenient provider mapper; null if it is not JSON. */
    static JsonNode parse(String body) {
        try {
            return ProviderJson.MAPPER.readTree(body == null ? "" : body);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** The answer's {@code error} object, or null. */
    static JsonNode error(JsonNode json) {
        return json == null ? null : json.get("error");
    }

    /** {@code error.code}, if it is an integer. */
    static Integer metaCode(JsonNode error) {
        JsonNode code = error == null ? null : error.get("code");
        return code != null && code.isIntegralNumber() && code.canConvertToInt() ? code.intValue() : null;
    }

    /** {@code error.fbtrace_id} if it looks like one, "invalid" if not, "none" if absent. */
    static String traceId(JsonNode error) {
        JsonNode trace = error == null ? null : error.get("fbtrace_id");
        if (trace == null || !trace.isString()) return "none";
        return TRACE_ID.matcher(trace.asString()).matches() ? trace.asString() : "invalid";
    }

    /** A Retry-After in seconds, if Meta sends one (its pages document none). */
    static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers()
                .firstValue("retry-after")
                .filter(value -> value.matches("\\d{1,6}"))
                .map(value -> Duration.ofSeconds(Long.parseLong(value)));
    }
}
