package com.northstar.tenancy.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/**
 * Writes the {code, message, details} error body from filters, before Spring MVC's exception handling exists. Codes
 * and messages are fixed strings chosen here, never request data, so no escaping is needed. Step 1.6 brings the
 * general error handler.
 */
final class ApiErrors {

    private ApiErrors() {}

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":{}}");
    }
}
