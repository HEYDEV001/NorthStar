package com.northstar.jobs;

import java.util.UUID;
import org.jobrunr.jobs.lambdas.JobRequest;

/**
 * A job that works on one tenant's data. Its tenant ID is stored with the job and becomes the worker's tenant scope
 * before anything else runs (P10). Implementations are records of IDs only: no names, phones or message text.
 */
public interface TenantJobRequest extends JobRequest {

    UUID tenantId();
}
