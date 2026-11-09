package com.northstar.api;

import java.util.Map;

/**
 * The body of every error the API returns: a stable machine-readable code, a short message for people, and details
 * (field errors, the request ID). Never exception text, class names, SQL, constraint names or submitted values.
 */
public record ApiError(String code, String message, Map<String, Object> details) {

    public ApiError {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
