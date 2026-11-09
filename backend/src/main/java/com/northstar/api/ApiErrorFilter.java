package com.northstar.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wraps everything after the request ID, Spring Security included, so a failure inside a filter (for example the
 * membership lookup when the database is down) still answers in the {code, message, details} format, never with a
 * stack trace or an HTML page. Controller errors are already handled by {@link ApiExceptionHandler}; this catches what
 * escapes the filters.
 */
final class ApiErrorFilter extends OncePerRequestFilter {

    private final ApiErrorMapper errors;
    private final ApiErrorWriter writer;

    ApiErrorFilter(ApiErrorMapper errors, ApiErrorWriter writer) {
        this.errors = errors;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException failure) {
            if (response.isCommitted()) throw failure;
            Throwable cause = failure instanceof ServletException servlet && servlet.getRootCause() != null
                    ? servlet.getRootCause()
                    : failure;
            ApiErrorMapper.Mapped mapped = errors.map(cause);
            errors.logIfUnexpected(mapped, cause, request);
            writer.write(response, mapped.status(), mapped.error());
        }
    }
}
