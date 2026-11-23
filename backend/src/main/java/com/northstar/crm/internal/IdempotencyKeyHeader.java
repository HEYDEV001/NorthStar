package com.northstar.crm.internal;

import com.northstar.api.BadRequestException;
import com.northstar.tenancy.RequestKeys;

/**
 * The {@value #NAME} request header (P21): required on the writes that take it (the staff reply, step 2.9b; manual lead
 * entry, step 2.10), 1 to {@value RequestKeys#MAX_KEY_LENGTH} of {@code [A-Za-z0-9_.:-]}, e.g. a UUID. Never logged.
 */
final class IdempotencyKeyHeader {

    static final String NAME = "Idempotency-Key";

    private IdempotencyKeyHeader() {}

    /**
     * The key, or 400 {@code idempotency_key_required} when it is missing and {@code idempotency_key_invalid} when it
     * is malformed. {@code request} names the write in the message, e.g. "A reply".
     */
    static String require(String value, String request) {
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
