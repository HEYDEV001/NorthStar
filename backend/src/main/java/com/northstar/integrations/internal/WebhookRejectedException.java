package com.northstar.integrations.internal;

import com.northstar.api.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A webhook request refused on purpose (step 2.2): a bad signature (401), a failed verification handshake (403), a body
 * over the limit (413), or webhooks not configured on this process (503). Answered in the standard error format with
 * the request ID; the message never says more than the code.
 */
final class WebhookRejectedException extends ApiException {

    private WebhookRejectedException(HttpStatus status, String code, String message) {
        super(status, code, message, Map.of());
    }

    static WebhookRejectedException invalidSignature() {
        return new WebhookRejectedException(
                HttpStatus.UNAUTHORIZED, "invalid_signature", "The webhook signature is missing or invalid");
    }

    static WebhookRejectedException verificationFailed() {
        return new WebhookRejectedException(
                HttpStatus.FORBIDDEN, "verification_failed", "The webhook verification request was refused");
    }

    static WebhookRejectedException tooLarge() {
        return new WebhookRejectedException(
                HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The webhook body is too large");
    }

    static WebhookRejectedException notConfigured() {
        return new WebhookRejectedException(
                HttpStatus.SERVICE_UNAVAILABLE, "webhooks_not_configured", "Webhooks are not configured here");
    }
}
