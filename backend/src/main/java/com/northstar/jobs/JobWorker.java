package com.northstar.jobs;

import static org.jobrunr.scheduling.RecurringJobBuilder.aRecurringJob;

import java.util.List;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.springframework.context.SmartLifecycle;

/**
 * The background job server, present only when northstar.worker.enabled is true (the worker container, local runs
 * and the worker tests). On start it schedules the recurring platform jobs (rows in JobRunr's own tables only), then
 * starts processing; on shutdown it stops taking jobs.
 */
final class JobWorker implements SmartLifecycle {

    private final BackgroundJobServer server;
    private final JobRequestScheduler scheduler;
    private final List<RecurringPlatformJob> recurringJobs;

    JobWorker(BackgroundJobServer server, JobRequestScheduler scheduler, List<RecurringPlatformJob> recurringJobs) {
        this.server = server;
        this.scheduler = scheduler;
        this.recurringJobs = recurringJobs;
    }

    @Override
    public void start() {
        for (RecurringPlatformJob job : recurringJobs) {
            scheduler.createRecurrently(aRecurringJob()
                    .withId(job.id())
                    .withInterval(job.interval())
                    .withAmountOfRetries(0)
                    .withJobRequest(job.request()));
        }
        server.start();
    }

    @Override
    public void stop() {
        server.stop();
    }

    @Override
    public boolean isRunning() {
        return server.isRunning();
    }
}
