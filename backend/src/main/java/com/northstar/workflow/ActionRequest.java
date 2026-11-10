package com.northstar.workflow;

import java.util.UUID;

/**
 * One attempt at an action, as a handler sees it. The idempotency key goes to providers that support idempotency
 * headers, so a repeated attempt never repeats the side effect there.
 */
public record ActionRequest(
        UUID tenantId, UUID actionId, String type, String idempotencyKey, String payloadJson, int attempt) {}
