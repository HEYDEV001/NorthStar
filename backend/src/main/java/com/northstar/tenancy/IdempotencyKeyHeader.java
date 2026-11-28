package com.northstar.tenancy;

import com.northstar.api.BadRequestException;

/**
 * The {@value #NAME} request header (P21): required on the writes that take it (the staff reply, step 2.9b; manual lead
 * entry, step 2.10; the workflow editor's writes, step 3.1b; a lead's stage move, step 3.3b), 1 to
 * {@value RequestKeys#MAX_KEY_LENGTH} of
 * {@code [A-Za-z0-9_.:-]}, e.g. a UUID. Never logged. Here, beside {@link RequestKeys}, since step 3.1b, so every module
 * with such a write reads it the same way.
 */
public final class IdempotencyKeyHeader {

    public static final String NAME = "Idempotency-Key";

    private IdempotencyKeyHeader() {}

    /**
     * The key, or 400 {@code idempotency_key_required} when it is missing and {@code idempotency_key_invalid} when it
     * is malformed. {@code request} names the write in the message, e.g. "A reply".
     */
    public static String require(String value, String request) {
        if (value == null) {
            throw new BadRequestException("idempotency_key_required", request + " needs an Idempotency-Key header");
        }
        if (!RequestKeys.KEY.matcher(value).matches()) {
            throw new BadRequestException(
                    "idempotency_key_invalid",
                    "An Idempotency-Key is 1 to " + RequestKeys.MAX_KEY_LENGTH
                            + " letters, digits, '_', '.', ':' or '-'");
        }
        return value;
    }
}
