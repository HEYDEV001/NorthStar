package com.northstar.tenancy.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Invite tokens (step 1.9): 32 random bytes from SecureRandom, base64url without padding (43 characters). Only the
 * SHA-256 hash is stored; the raw token exists only in the invite link and is never logged, listed or audited.
 */
final class InviteTokens {

    static final int RANDOM_BYTES = 32;
    static final String FORMAT = "[A-Za-z0-9_-]{43}";

    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteTokens() {}

    static String newToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
