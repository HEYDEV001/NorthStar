package com.northstar.integrations.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * A tenant credential in plaintext: a business token, refresh token or API key (integration-specs.md section 7). It
 * exists only inside the worker, between {@link CredentialStore} and the adapter that sends it to the provider.
 *
 * <p>Built so it cannot leak by accident: {@link #toString()} is always {@code Secret[redacted]}, so a log line, an
 * exception message, a record or a collection that holds it prints nothing of the value; equality is identity (no
 * content comparison to time); it is not serializable; and {@link #close()} overwrites the bytes. Use it in
 * try-with-resources and read it only through {@link #use} or, where a provider client needs a string, {@link #utf8}.
 */
final class Secret implements AutoCloseable {

    static final String REDACTED = "Secret[redacted]";

    private final byte[] value;
    private volatile boolean closed;

    private Secret(byte[] value) {
        this.value = value;
    }

    /** Takes ownership of the bytes: the caller must not keep, read or reuse the array afterwards. */
    static Secret wrap(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length == 0) throw new IllegalArgumentException("A secret cannot be empty");
        return new Secret(value);
    }

    static Secret ofUtf8(String value) {
        Objects.requireNonNull(value, "value");
        return wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs the work on the plaintext bytes themselves, not a copy. The work must not keep the array or change it, and
     * must not put it in a log or an exception.
     */
    <T> T use(Function<byte[], T> work) {
        if (closed) throw new IllegalStateException("The secret has been closed");
        return work.apply(value);
    }

    /**
     * The plaintext as a string, for a provider client that takes one (an Authorization header). A string cannot be
     * wiped, so call this only where the value is sent, and do not keep the result.
     */
    String utf8() {
        return use(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /** Overwrites the bytes with zeros; any later use fails. */
    @Override
    public void close() {
        closed = true;
        Arrays.fill(value, (byte) 0);
    }

    @Override
    public String toString() {
        return REDACTED;
    }
}
