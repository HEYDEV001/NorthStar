package com.northstar.jobs;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import org.jobrunr.jobs.filters.RetryFilter;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.BackgroundJobServerConfiguration;
import org.jobrunr.server.JobActivator;
import org.jobrunr.server.carbonaware.CarbonAwareJobProcessingConfiguration;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider;
import org.jobrunr.utils.mapper.JsonMapper;
import org.jobrunr.utils.mapper.jackson3.Jackson3JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires JobRunr by hand (step 1.10, data-model.md section 12).
 *
 * <p>JobRunr connects through its own small pool as jobrunr_user, which may use only the jobrunr schema: any query on
 * northstar from it fails with "permission denied for schema northstar". The pool is not a bean, so nothing else can
 * inject it, and the app's tenant-guarded pool cannot reach JobRunr's tables either. JobRunr needs no session state
 * (no advisory locks, LISTEN/NOTIFY or session SET; its only locks are FOR UPDATE SKIP LOCKED inside transactions), so
 * it uses the transaction pooler like the app, with prepareThreshold=0.
 *
 * <p>Off on purpose: JobRunr's table creation (Flyway owns the schema), its dashboard (never created, and it is the
 * only part with anonymous usage reporting), carbon-aware scheduling (it calls an external API), and JobRunr's own
 * retries (actions keep their own retry budget; any other failed job stays failed).
 */
@Configuration(proxyBeanMethods = false)
class JobsConfiguration {

    static final String TABLE_PREFIX = "jobrunr.";

    private final JsonMapper jsonMapper = new Jackson3JsonMapper();

    @Bean
    @DependsOnDatabaseInitialization
    StorageProvider jobRunrStorageProvider(
            @Value("${northstar.jobs.database.url}") String url,
            @Value("${northstar.jobs.database.username}") String username,
            @Value("${northstar.jobs.database.password}") String password,
            @Value("${northstar.jobs.database.pool-size}") int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("jobrunr");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.addDataSourceProperty("prepareThreshold", 0);
        HikariDataSource pool = new HikariDataSource(config);
        try {
            JobRunrStorage storage = new JobRunrStorage(pool);
            storage.setJobMapper(new JobMapper(jsonMapper));
            return storage;
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }
    }

    @Bean
    Jobs jobs(StorageProvider storageProvider) {
        return new Jobs(new JobRequestScheduler(storageProvider));
    }

    @Bean
    @ConditionalOnBooleanProperty("northstar.worker.enabled")
    JobWorker jobWorker(
            StorageProvider storageProvider,
            ApplicationContext context,
            List<RecurringPlatformJob> recurringJobs,
            @Value("${northstar.worker.threads}") int threads,
            @Value("${northstar.worker.poll-interval}") Duration pollInterval) {
        BackgroundJobServerConfiguration configuration =
                BackgroundJobServerConfiguration.usingStandardBackgroundJobServerConfiguration()
                        .andName("northstar-worker")
                        .andWorkerCount(threads)
                        .andPollInterval(pollInterval)
                        .andCarbonAwareJobProcessingConfiguration(
                                CarbonAwareJobProcessingConfiguration
                                        .usingDisabledCarbonAwareJobProcessingConfiguration());
        BackgroundJobServer server =
                new BackgroundJobServer(storageProvider, jsonMapper, handlersOnly(context), configuration);
        server.setJobFilters(List.of(new RetryFilter(0)));
        return new JobWorker(server, new JobRequestScheduler(storageProvider), recurringJobs);
    }

    /**
     * JobRunr asks the activator for the object that runs a job. Only our tenant and platform handlers are handed out,
     * so a job naming any other bean (a lambda job written straight into the table, bypassing {@link Jobs}) fails.
     */
    static JobActivator handlersOnly(ApplicationContext context) {
        return new JobActivator() {
            @Override
            public <T> T activateJob(Class<T> type) {
                if (!TenantJobHandler.class.isAssignableFrom(type)
                        && !PlatformJobHandler.class.isAssignableFrom(type)) {
                    throw new JobTenantException("Only tenant and platform job handlers may run");
                }
                return context.getBean(type);
            }
        };
    }

    /** JobRunr's Postgres storage on the private pool, which it closes when Spring closes the storage. */
    private static final class JobRunrStorage extends PostgresStorageProvider {

        private final HikariDataSource pool;

        JobRunrStorage(HikariDataSource pool) {
            super(pool, TABLE_PREFIX, DatabaseOptions.SKIP_CREATE);
            this.pool = pool;
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                pool.close();
            }
        }
    }
}
