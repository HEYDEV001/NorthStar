package com.northstar.jobs;

/** A tenant job with no tenant, or with a tenant that does not exist (P10). The job fails and is never retried. */
public class JobTenantException extends RuntimeException {

    JobTenantException(String message) {
        super(message);
    }
}
