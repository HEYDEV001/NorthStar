package com.northstar.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The request is well-formed JSON with valid fields but cannot be used as sent, e.g. a paging cursor that was not issued
 * by this API (step 2.9a). Field-level problems are Jakarta validation's {@code validation_failed}; this is for a value
 * only the service can judge. The message never repeats what the client sent.
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message, Map.of());
    }
}
