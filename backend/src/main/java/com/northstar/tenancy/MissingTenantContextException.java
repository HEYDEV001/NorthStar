package com.northstar.tenancy;

/**
 * Database access was attempted without a tenant context, or outside a transaction that set it. This is always a bug:
 * it is thrown before any query runs, never caught and swallowed, and surfaces as a 500 with an error log.
 *
 * <p>A plain RuntimeException on purpose: Spring's repository proxies translate IllegalStateException into
 * InvalidDataAccessApiUsageException, which would hide this behind a generic data error.
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
