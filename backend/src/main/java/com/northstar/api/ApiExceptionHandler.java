package com.northstar.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one error handler for every controller (step 1.6). Spring MVC's own errors (unknown route, wrong method,
 * unreadable body, unsupported media type...) arrive through {@link ResponseEntityExceptionHandler} and are re-dressed
 * in our format; everything else goes through {@link ApiErrorMapper}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private final ApiErrorMapper errors;

    ApiExceptionHandler(ApiErrorMapper errors) {
        this.errors = errors;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handle(Exception failure, HttpServletRequest request) {
        ApiErrorMapper.Mapped mapped = errors.map(failure);
        errors.log(mapped, failure, request);
        return ResponseEntity.status(mapped.status()).body(mapped.error());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception failure, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(errors.forStatus(status), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException invalid, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> fields = new ArrayList<>();
        for (FieldError error : invalid.getBindingResult().getFieldErrors()) {
            fields.add(ApiErrorMapper.field(error.getField(), error.getCode(), error.getDefaultMessage()));
        }
        invalid.getBindingResult()
                .getGlobalErrors()
                .forEach(error -> fields.add(
                        ApiErrorMapper.field(error.getObjectName(), error.getCode(), error.getDefaultMessage())));
        return validationFailed(fields, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException invalid, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> fields = new ArrayList<>();
        invalid.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                fields.add(ApiErrorMapper.field(parameter, ruleOf(error), error.getDefaultMessage()));
            }
        });
        return validationFailed(fields, headers);
    }

    private ResponseEntity<Object> validationFailed(List<Map<String, String>> fields, HttpHeaders headers) {
        ApiErrorMapper.Mapped mapped = errors.validationFailed(fields);
        return new ResponseEntity<>(mapped.error(), headers, mapped.status());
    }

    private static String ruleOf(MessageSourceResolvable error) {
        if (error instanceof DefaultMessageSourceResolvable resolvable && resolvable.getCode() != null) {
            return resolvable.getCode();
        }
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? "invalid" : codes[codes.length - 1];
    }
}
