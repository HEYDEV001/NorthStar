package com.northstar.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Too many attempts in a short time; the caller should wait and try again. */
public class RateLimitedException extends ApiException {

    public RateLimitedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many attempts; please wait and try again", Map.of());
    }
}
