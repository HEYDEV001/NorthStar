package com.northstar.integrations.internal;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Meta's webhook signature (step 2.2, P15): {@code X-Hub-Signature-256: sha256=<hex>}, the HMAC-SHA256 of the raw request
 * body keyed with the app secret (developers.facebook.com/docs/graph-api/webhooks/getting-started, "Validate
 * Payloads"). Checked on the exact bytes received, before any JSON parsing: Meta signs the bytes it sends, so a body
 * parsed and serialised again would not match.
 *
 * <p>The header must be exactly {@code sha256=} and 64 lowercase hex digits; anything else is invalid. The comparison
 * is constant time.
 */
final class WebhookSignature {

    static final String WHATSAPP_HEADER = "X-Hub-Signature-256";

    private static final String PREFIX = "sha256=";
    private static final int HEX_LENGTH = 64;

    private WebhookSignature() {}

    static boolean matches(byte[] key, byte[] body, String header) {
        if (header == null || header.length() != PREFIX.length() + HEX_LENGTH || !header.startsWith(PREFIX)) {
            return false;
        }
        String hex = header.substring(PREFIX.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return MessageDigest.isEqual(hmacSha256(key, body), HexFormat.of().parseHex(hex));
    }

    static byte[] hmacSha256(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
