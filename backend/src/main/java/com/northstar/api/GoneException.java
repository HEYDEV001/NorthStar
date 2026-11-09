package com.northstar.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** The thing existed but can no longer be used, e.g. an expired, revoked or already used invite. */
public class GoneException extends ApiException {

    public GoneException(String code, String message) {
        super(HttpStatus.GONE, code, message, Map.of());
    }
}
