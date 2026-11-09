package com.northstar.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes an {@link ApiError} straight to the response, for filters and the /error fallback, always as JSON. */
@Component
public class ApiErrorWriter {

    private final JsonMapper json;

    ApiErrorWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, HttpStatusCode status, ApiError error) throws IOException {
        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(error));
        response.flushBuffer();
    }
}
