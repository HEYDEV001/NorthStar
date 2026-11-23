package com.northstar.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 422: a request that is well-formed but cannot be processed as sent. Step 2.9b uses it for an {@code Idempotency-Key}
 * reused with a different request ({@code idempotency_key_reused}), as the IETF Idempotency-Key header draft says. The
 * message never repeats what the client sent.
 */
public class UnprocessableException extends ApiException {

    public UnprocessableException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message, Map.of());
    }
}
