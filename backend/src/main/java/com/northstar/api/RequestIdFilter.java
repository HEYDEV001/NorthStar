package com.northstar.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The outermost filter: gives every request an ID, returns it as X-Request-Id on every response (errors included) and
 * puts it on every log line, then clears it. A caller's X-Request-Id is reused only if it is a short [A-Za-z0-9-]
 * string, so nothing a client sends can inject text into the logs.
 */
final class RequestIdFilter extends OncePerRequestFilter {

    static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(LogContext.REQUEST_ID_HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(LogContext.REQUEST_ID_HEADER, requestId);
        withRequestId(requestId, request, response, chain);
    }

    /** The /error dispatch runs after the first pass has cleared the log context; restore the same ID for it. */
    @Override
    protected void doFilterNestedErrorDispatch(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object requestId = request.getAttribute(ATTRIBUTE);
        if (requestId instanceof String id) {
            withRequestId(id, request, response, chain);
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private static void withRequestId(
            String requestId, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(LogContext.REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LogContext.REQUEST_ID);
        }
    }
}
