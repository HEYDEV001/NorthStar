package com.northstar.jobs;

import com.zaxxer.hikari.HikariDataSource;

/** Test-only: JobRunr's private pool, built exactly as the app builds it, for tests outside this package. */
public final class JobRunrPoolProbe {

    private JobRunrPoolProbe() {}

    /** A one-connection pool; the caller closes it. */
    public static HikariDataSource pool(String url, String username, String password) {
        return JobsConfiguration.jobRunrPool(url, username, password, 1);
    }
}
