package com.northstar.tenancy.internal;

import com.northstar.tenancy.MissingTenantContextException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Database access without a tenant context is a bug, never a client error: answer 500 and log it at error level so
 * it alerts. The response says nothing about why. Step 1.6 folds this into the global error handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class MissingTenantContextAdvice {

    private static final Logger log = LoggerFactory.getLogger(MissingTenantContextAdvice.class);

    @ExceptionHandler(MissingTenantContextException.class)
    ResponseEntity<Map<String, Object>> handle(MissingTenantContextException e) {
        log.error("Database access without tenant context", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "internal_error", "message", "Internal error", "details", Map.of()));
    }
}
