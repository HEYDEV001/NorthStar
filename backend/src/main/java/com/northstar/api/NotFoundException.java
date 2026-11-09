package com.northstar.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The record does not exist for this caller. Another tenant's record is not visible inside the tenant scope (RLS and
 * @TenantId), so it is reported exactly like a missing one and its existence is never revealed (P9). Always the same
 * code and message, whatever the record.
 */
public class NotFoundException extends ApiException {

    public NotFoundException() {
        super(HttpStatus.NOT_FOUND, "not_found", "Not found", Map.of());
    }
}
