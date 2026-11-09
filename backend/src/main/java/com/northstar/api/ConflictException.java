package com.northstar.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The request clashes with current state, e.g. a slug already taken. Services check first and throw this with a
 * specific code; a raw database conflict only ever becomes the generic 409, with no constraint, column or value.
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message, Map.of());
    }

    /** With details the caller can act on, such as the ID of the record it clashed with. Never personal data. */
    public ConflictException(String code, String message, Map<String, Object> details) {
        super(HttpStatus.CONFLICT, code, message, details);
    }
}
