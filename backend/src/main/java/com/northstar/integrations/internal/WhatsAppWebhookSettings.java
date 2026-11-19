package com.northstar.integrations.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The WhatsApp webhook's two platform secrets (step 2.2, integration-specs.md section 7): Meta's app secret, which signs
 * every delivery, and the verify token for Meta's subscription handshake. Both live only on the components that serve
 * the webhook (the api component on App Platform), as encrypted variables.
 *
 * <p>When the endpoints are active:
 *
 * <ul>
 *   <li>both secrets set: active, whatever NORTHSTAR_WORKER_ENABLED says (local development runs one process with the
 *       worker on that also serves the API);
 *   <li>either missing on a process with the worker off (an API container): the app refuses to start, naming the
 *       setting;
 *   <li>either missing on a process with the worker on: it starts, and the webhook paths answer 503.
 * </ul>
 *
 * A secret that is set but malformed refuses startup in every case. Errors and logs name the setting, never its value;
 * {@link #toString()} is redacted.
 */
final class WhatsAppWebhookSettings {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookSettings.class);

    static final String APP_SECRET_VARIABLE = "NORTHSTAR_WHATSAPP_APP_SECRET";
    static final String VERIFY_TOKEN_VARIABLE = "NORTHSTAR_WHATSAPP_VERIFY_TOKEN";

    /** Meta's app secrets are 32 hex characters; generated verify tokens are at least as long. */
    static final int MIN_SECRET_LENGTH = 32;

    private static final int MAX_SECRET_LENGTH = 512;

    private final byte[] appSecret;
    private final byte[] verifyToken;

    private WhatsAppWebhookSettings(byte[] appSecret, byte[] verifyToken) {
        this.appSecret = appSecret;
        this.verifyToken = verifyToken;
    }

    /** Applies the rules above; throws at startup with a message naming the setting. */
    static WhatsAppWebhookSettings of(String appSecret, String verifyToken, boolean workerEnabled) {
        boolean hasAppSecret = appSecret != null && !appSecret.isBlank();
        boolean hasVerifyToken = verifyToken != null && !verifyToken.isBlank();
        if (hasAppSecret) requireWellFormed(APP_SECRET_VARIABLE, appSecret);
        if (hasVerifyToken) requireWellFormed(VERIFY_TOKEN_VARIABLE, verifyToken);
        if (hasAppSecret && hasVerifyToken) {
            return new WhatsAppWebhookSettings(
                    appSecret.getBytes(StandardCharsets.UTF_8), verifyToken.getBytes(StandardCharsets.UTF_8));
        }
        String missing = hasAppSecret ? VERIFY_TOKEN_VARIABLE : APP_SECRET_VARIABLE;
        if (!workerEnabled) {
            throw new IllegalStateException(missing + " is required when the worker is off (an API container)");
        }
        log.warn("webhook.not_configured provider=whatsapp missing={}: the webhook paths answer 503", missing);
        return new WhatsAppWebhookSettings(null, null);
    }

    private static void requireWellFormed(String variable, String value) {
        if (value.length() < MIN_SECRET_LENGTH
                || value.length() > MAX_SECRET_LENGTH
                || !value.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalStateException(variable + " must be " + MIN_SECRET_LENGTH + " to " + MAX_SECRET_LENGTH
                    + " printable ASCII characters with no spaces");
        }
    }

    /** False only on a worker-on process missing a secret: the webhook paths then answer 503. */
    boolean configured() {
        return appSecret != null;
    }

    /** The HMAC key for X-Hub-Signature-256: the app secret's bytes. Present only when {@link #configured()}. */
    Optional<byte[]> appSecret() {
        return Optional.ofNullable(appSecret).map(byte[]::clone);
    }

    /** Constant-time comparison with the configured verify token; false when not configured. */
    boolean verifyTokenMatches(String candidate) {
        if (verifyToken == null || candidate == null) return false;
        return MessageDigest.isEqual(verifyToken, candidate.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "WhatsAppWebhookSettings[configured=" + configured() + ", secrets redacted]";
    }
}
