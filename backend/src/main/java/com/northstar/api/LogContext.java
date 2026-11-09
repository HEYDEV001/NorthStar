package com.northstar.api;

/**
 * Keys every log line carries (logging.pattern.correlation): the request ID always, and the tenant and user while a
 * request runs inside the tenant scope. IDs only, never names, phone numbers or message text.
 */
public final class LogContext {

    public static final String REQUEST_ID = "request_id";
    public static final String TENANT_ID = "tenant_id";
    public static final String USER_ID = "user_id";

    /** The response header that returns the request ID, so a user's error report can be matched to the logs. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private LogContext() {}
}
