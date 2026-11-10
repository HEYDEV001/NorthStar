/**
 * Background jobs (step 1.10): JobRunr on its own private pool, and the only way work runs outside a web request.
 * Shared infrastructure, not a business module: it owns only the jobrunr schema and depends only on tenancy's public
 * API.
 *
 * <p>Every job is a {@link com.northstar.jobs.TenantJobRequest}, run by a {@link com.northstar.jobs.TenantJobHandler}
 * inside the job's tenant scope as the system actor, or a {@link com.northstar.jobs.PlatformJobRequest}, which has no
 * tenant and may only read tenant IDs and enqueue tenant jobs. Jobs are created only through {@link
 * com.northstar.jobs.Jobs}; JobRunr's schedulers are not beans.
 */
package com.northstar.jobs;
