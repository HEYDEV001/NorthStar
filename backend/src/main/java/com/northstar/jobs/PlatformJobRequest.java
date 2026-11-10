package com.northstar.jobs;

import org.jobrunr.jobs.lambdas.JobRequest;

/**
 * A job with no tenant, such as the action sweeper. It runs without a tenant scope and cannot open one (only this
 * package can create the system context), so it may only read tenant IDs through allowlisted lookups and enqueue
 * {@link TenantJobRequest}s.
 */
public interface PlatformJobRequest extends JobRequest {}
