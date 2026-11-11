package com.northstar.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The invite token, derived from the action (step 1.12): HMAC-SHA256(NORTHSTAR_INVITE_TOKEN_KEY, "invite-token:v1:" +
 * actionId), base64url without padding (43 characters, the format accept expects). Every attempt of one action gets the
 * same token, so a retry after a lost response sends a byte-identical email under the same Resend idempotency key and
 * Resend does not send it again: the invitee gets one email whose link works. The token is never stored; only its hash
 * reaches the database.
 *
 * <p>The key is a platform secret of at least 32 random bytes (base64 or base64url), required only when the worker is
 * on and checked at startup without the database. Changing it affects only invite emails still being retried: their
 * body changes, Resend refuses the repeated key, the action fails with an alert, and the admin resends the invite.
 */
@Component
class InviteTokenDerivation {

    static final String PREFIX = "invite-token:v1:";
    static final int MIN_KEY_BYTES = 32;

    private final byte[] key;

    InviteTokenDerivation(
            @Value("${northstar.invites.token-key}") String encodedKey,
            @Value("${northstar.worker.enabled}") boolean workerEnabled) {
        byte[] decoded = decode(encodedKey);
        if (workerEnabled && decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "NORTHSTAR_INVITE_TOKEN_KEY must be at least 32 random bytes, base64, when the worker is on");
        }
        this.key = decoded;
    }

    String tokenFor(UUID actionId) {
        if (key.length < MIN_KEY_BYTES) throw new IllegalStateException("NORTHSTAR_INVITE_TOKEN_KEY is not set");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] token = mac.doFinal((PREFIX + actionId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }

    private static byte[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return new byte[0];
        String trimmed = encoded.trim();
        try {
            return Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException notStandard) {
            try {
                return Base64.getUrlDecoder().decode(trimmed);
            } catch (IllegalArgumentException notUrl) {
                throw new IllegalStateException("NORTHSTAR_INVITE_TOKEN_KEY must be base64");
            }
        }
    }
}
