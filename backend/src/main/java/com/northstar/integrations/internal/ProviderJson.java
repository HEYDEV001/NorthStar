package com.northstar.integrations.internal;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lenient JSON mapper for provider payloads (CLAUDE.md, API conventions): providers add fields without notice, so
 * unknown fields are ignored. Never used for Northstar's own API bodies, which the app's strict mapper reads.
 */
final class ProviderJson {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ProviderJson() {}
}
