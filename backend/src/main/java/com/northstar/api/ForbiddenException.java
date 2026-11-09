package com.northstar.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The caller may see this record but not do this to it, e.g. staff calling an admin action in their own tenant. Never
 * for another tenant's record: that is {@link NotFoundException}.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message, Map.of());
    }
}
