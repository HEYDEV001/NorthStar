package com.northstar.api;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.StaleStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Turns any failure into a status and an {@link ApiError}, for the exception handler, the error filter and the /error
 * fallback alike, so every path answers the same way. Anything not recognised here, a missing tenant context included,
 * is unexpected: a 500 that says nothing about the cause, with the cause logged alongside the request ID.
 */
@Component
public class ApiErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorMapper.class);

    /** A mapped failure. Unexpected ones are logged at error level with their stack trace. */
    public record Mapped(HttpStatus status, ApiError error, boolean unexpected) {}

    public Mapped map(Throwable failure) {
        ApiException expected = find(failure, ApiException.class);
        if (expected != null) {
            return new Mapped(
                    expected.status(), error(expected.code(), expected.getMessage(), expected.details()), false);
        }
        if (databaseUnavailable(failure)) {
            return new Mapped(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    error("service_unavailable", "The service is temporarily unavailable", Map.of()),
                    false);
        }
        if (staleVersion(failure)) {
            return new Mapped(
                    HttpStatus.CONFLICT,
                    error("stale_version", "This record was changed by someone else; reload and try again", Map.of()),
                    false);
        }
        ConstraintViolationException invalid = find(failure, ConstraintViolationException.class);
        if (invalid != null) {
            List<Map<String, String>> fields = invalid.getConstraintViolations().stream()
                    .map(v -> field(
                            v.getPropertyPath().toString(),
                            v.getConstraintDescriptor()
                                    .getAnnotation()
                                    .annotationType()
                                    .getSimpleName(),
                            v.getMessage()))
                    .toList();
            return validationFailed(fields);
        }
        Mapped constraint = databaseConstraint(find(failure, SQLException.class));
        return constraint != null ? constraint : internalError();
    }

    /** The standard error for a status Spring MVC decided on (unknown route, wrong method, unreadable body...). */
    public ApiError forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> error("malformed_request", "The request could not be read", Map.of());
            case 401 -> error("unauthorized", "Authentication required", Map.of());
            case 403 -> error("forbidden", "Not allowed", Map.of());
            case 404 -> error("not_found", "Not found", Map.of());
            case 405 -> error("method_not_allowed", "This method is not allowed here", Map.of());
            case 406 -> error("not_acceptable", "Responses are JSON only", Map.of());
            case 415 -> error("unsupported_media_type", "Request bodies must be JSON", Map.of());
            case 503 -> error("service_unavailable", "The service is temporarily unavailable", Map.of());
            default ->
                status.is4xxClientError()
                        ? error("bad_request", "The request could not be processed", Map.of())
                        : error("internal_error", "Internal error", Map.of());
        };
    }

    public Mapped validationFailed(List<Map<String, String>> fields) {
        return new Mapped(
                HttpStatus.BAD_REQUEST,
                error("validation_failed", "The request is not valid", Map.of("fields", fields)),
                false);
    }

    /** A field error: the field, the rule it broke, and the rule's message. Never the submitted value. */
    public static Map<String, String> field(String field, String rule, String message) {
        Map<String, String> error = new LinkedHashMap<>();
        error.put("field", field);
        error.put("rule", rule);
        error.put("message", message);
        return error;
    }

    /** An error with the current request ID added to its details. */
    public ApiError error(String code, String message, Map<String, Object> details) {
        Map<String, Object> withRequestId = new LinkedHashMap<>(details);
        String requestId = MDC.get(LogContext.REQUEST_ID);
        if (requestId != null) withRequestId.put("requestId", requestId);
        return new ApiError(code, message, withRequestId);
    }

    /**
     * Logs what the logs need to see: unexpected failures with their stack trace, an unavailable database, and every
     * by-ID lookup that found nothing (P9: attempts are logged; another tenant's ID looks like a missing one). Only
     * the record type and the UUID are logged, never other client input or the query string.
     */
    public void log(Mapped mapped, Throwable failure, HttpServletRequest request) {
        NotFoundException notFound = find(failure, NotFoundException.class);
        if (notFound != null) {
            log.info("Not found: {} {}", notFound.recordType(), notFound.requestedId());
        } else if (mapped.unexpected()) {
            log.error("Unexpected failure on {} {}", request.getMethod(), request.getRequestURI(), failure);
        } else if (mapped.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            log.warn(
                    "Database unavailable on {} {}: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    failure.getClass().getSimpleName());
        }
    }

    private Mapped internalError() {
        return new Mapped(HttpStatus.INTERNAL_SERVER_ERROR, error("internal_error", "Internal error", Map.of()), true);
    }

    /**
     * Database constraint failures are the backstop behind service checks. The response never names the constraint,
     * column or value; a service that wants a specific message checks first and throws its own exception. A foreign
     * key failure reads the same whether the referenced record is missing or belongs to another tenant (P9).
     */
    private Mapped databaseConstraint(SQLException sql) {
        if (sql == null || sql.getSQLState() == null) return null;
        return switch (sql.getSQLState()) {
            case "23505" ->
                new Mapped(
                        HttpStatus.CONFLICT,
                        error("conflict", "This conflicts with an existing record", Map.of()),
                        false);
            case "23503" ->
                new Mapped(
                        HttpStatus.BAD_REQUEST,
                        error("invalid_reference", "A referenced record does not exist", Map.of()),
                        false);
            case "23514" -> validationFailed(List.of());
            default -> null;
        };
    }

    private static boolean databaseUnavailable(Throwable failure) {
        if (find(failure, CannotGetJdbcConnectionException.class) != null
                || find(failure, CannotCreateTransactionException.class) != null
                || find(failure, DataAccessResourceFailureException.class) != null
                || find(failure, SQLTransientConnectionException.class) != null
                || find(failure, SQLNonTransientConnectionException.class) != null) {
            return true;
        }
        SQLException sql = find(failure, SQLException.class);
        return sql != null && sql.getSQLState() != null && sql.getSQLState().startsWith("08");
    }

    private static boolean staleVersion(Throwable failure) {
        return find(failure, OptimisticLockingFailureException.class) != null
                || find(failure, OptimisticLockException.class) != null
                || find(failure, StaleStateException.class) != null;
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return type.cast(cause);
            if (cause.getCause() == cause) break;
        }
        return null;
    }
}
