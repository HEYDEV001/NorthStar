package com.northstar.fakes;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook bodies the way WhatsApp and Razorpay do (integration-specs.md sections 2 and 3): HMAC-SHA256 of the raw
 * body, hex encoded, in a header. The header name and prefix come from the provider step that uses it (2.2, 5.5). The
 * secret is random per signer, never a fixture; {@link #secret()} hands it to the code under test.
 */
public final class HmacSigner {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String headerName;
    private final String prefix;
    private final String secret;

    public HmacSigner(String headerName, String prefix) {
        this.headerName = Objects.requireNonNull(headerName, "headerName");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        this.secret = HexFormat.of().formatHex(random);
    }

    public String headerName() {
        return headerName;
    }

    public String prefix() {
        return prefix;
    }

    /** The secret the system under test verifies with, as text (hex); HMAC keys are its UTF-8 bytes. */
    public String secret() {
        return secret;
    }

    /** The header value for this body: prefix + lowercase hex HMAC-SHA256, keyed with {@link #secret()}. */
    public String sign(byte[] body) {
        return prefix + HexFormat.of().formatHex(hmac(secret.getBytes(StandardCharsets.UTF_8), body));
    }

    static byte[] hmac(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
