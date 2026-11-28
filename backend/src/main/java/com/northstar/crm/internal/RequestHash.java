package com.northstar.crm.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The hash a request's Idempotency-Key is stored with (P21; data-model.md section 3, {@code request_keys}): SHA-256, in
 * lowercase hex, of the operation and the request's fields as the caller normalises them, each length-prefixed so no
 * two requests share an encoding (a missing field is {@code -}). The same key with another request is told apart
 * without storing what the request said. Never logged. Step 2.10's encoding, shared since step 3.3b.
 */
final class RequestHash {

    private RequestHash() {}

    static String of(String operation, String... fields) {
        StringBuilder canonical = new StringBuilder(operation).append(":v1");
        for (String field : fields) {
            canonical.append('|');
            if (field == null) {
                canonical.append('-');
            } else {
                canonical.append(field.length()).append(':').append(field);
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }
}
