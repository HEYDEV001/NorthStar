package com.northstar.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The last resort, replacing Spring Boot's /error page: anything the container still routes to /error (an error the
 * filters never saw) answers in the {code, message, details} format, as JSON, whatever the Accept header says.
 * A plain @Controller, so it is not moved under /api/v1.
 */
@Controller
class ApiErrorController implements ErrorController {

    private final ApiErrorMapper errors;
    private final ApiErrorWriter writer;

    ApiErrorController(ApiErrorMapper errors, ApiErrorWriter writer) {
        this.errors = errors;
        this.writer = writer;
    }

    @RequestMapping("${server.error.path:/error}")
    void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            ApiErrorMapper.Mapped mapped = errors.map(failure);
            errors.log(mapped, failure, request);
            writer.write(response, mapped.status(), mapped.error());
            return;
        }
        HttpStatus status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
                ? HttpStatus.resolve(code)
                : null;
        HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        writer.write(response, resolved, errors.forStatus(resolved));
    }
}
