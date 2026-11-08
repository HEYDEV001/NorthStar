package com.northstar.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API conventions (step 1.6): every @RestController lives under /api/v1, request bodies are JSON only, and the filters
 * run in a fixed order: request ID first, then the error filter, then Spring Security, so every response, errors
 * included, carries the request ID.
 */
@Configuration(proxyBeanMethods = false)
class ApiWebConfiguration implements WebMvcConfigurer {

    static final String API_PREFIX = "/api/v1";
    static final int REQUEST_ID_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    static final int ERROR_FILTER_ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1;

    @Override
    public void configurePathMatch(PathMatchConfigurer paths) {
        paths.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
    }

    @Override
    public void addInterceptors(InterceptorRegistry interceptors) {
        interceptors.addInterceptor(new JsonBodiesOnly());
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(REQUEST_ID_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiErrorFilter> apiErrorFilter(ApiErrorMapper errors, ApiErrorWriter writer) {
        FilterRegistrationBean<ApiErrorFilter> registration =
                new FilterRegistrationBean<>(new ApiErrorFilter(errors, writer));
        registration.setOrder(ERROR_FILTER_ORDER);
        return registration;
    }

    /** A request with a body must send it as JSON; anything else is a 415 in our format. */
    static final class JsonBodiesOnly implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws HttpMediaTypeNotSupportedException {
            boolean hasBody = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
            if (hasBody && !isJson(request.getContentType())) {
                throw new HttpMediaTypeNotSupportedException(
                        "Request bodies must be JSON", List.of(MediaType.APPLICATION_JSON));
            }
            return true;
        }

        private static boolean isJson(String contentType) {
            if (contentType == null) return false;
            try {
                MediaType type = MediaType.parseMediaType(contentType);
                return MediaType.APPLICATION_JSON.isCompatibleWith(type)
                        || type.getSubtype().endsWith("+json");
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
