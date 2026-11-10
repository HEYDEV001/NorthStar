package com.northstar.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import com.northstar.workflow.ActionMode;
import com.northstar.workflow.Actions;
import com.northstar.workflow.NewAction;
import com.northstar.workflow.internal.DueActionSweeper;
import com.northstar.workflow.internal.ExecuteAction;
import com.northstar.workflow.internal.SweepDueActions;
import java.time.Duration;
import java.util.UUID;
import org.jobrunr.scheduling.JobRequestScheduler;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The real worker (step 1.10): JobRunr's background server polls its tables through the private pool and runs jobs as
 * the system actor in each job's tenant (P10). Slower than the other tests: each check waits for a poll.
 */
@SpringBootTest(properties = {"northstar.worker.enabled=true", "northstar.worker.poll-interval=5s"})
@Import(TestcontainersConfiguration.class)
class JobWorkerTests {

    private static final Duration WAIT = Duration.ofSeconds(45);

    private final Actions actions;
    private final StorageProvider storage;
    private final DueActionSweeper sweeper;
    private final TestData data;
    private final JdbcClient admin;

    JobWorkerTests(
            @Autowired Actions actions,
            @Autowired StorageProvider storage,
            @Autowired DueActionSweeper sweeper,
            @Autowired PostgresAdmin postgres) {
        this.actions = actions;
        this.storage = storage;
        this.sweeper = sweeper;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void anActionRunsThroughTheWorker() {
        TestData.Member member = data.member("admin");
        UUID id = create(member);

        await().atMost(WAIT).until(() -> "succeeded".equals(status(id)));
    }

    @Test
    void theSweeperRecoversAnActionWhoseJobWasLost() {
        TestData.Member member = data.member("admin");
        UUID id = UUID.randomUUID();
        // A committed action whose job was never created (a crash between the commit and the enqueue).
        admin.sql("""
                        insert into northstar.actions (id, tenant_id, type, status, idempotency_key, mode, next_attempt_at)
                        values (:id, :t, 'test.scripted', 'pending', :key, 'automatic', now() - interval '1 second')""")
                .param("id", id)
                .param("t", member.tenantId())
                .param("key", "lost:" + id)
                .update();

        sweeper.run(new SweepDueActions());

        await().atMost(WAIT).until(() -> "succeeded".equals(status(id)));
    }

    @Test
    void P10_aJobWithoutATenantFails() {
        // A bug that bypasses Jobs (which refuses this) and writes the job straight into JobRunr's tables.
        UUID job = new JobRequestScheduler(storage)
                .enqueue(new ExecuteAction(null, UUID.randomUUID()))
                .asUUID();

        await().atMost(WAIT).until(() -> "FAILED".equals(jobState(job)));
        assertThat(admin.sql("select jobasjson from jobrunr.jobrunr_jobs where id = :id")
                        .param("id", job.toString())
                        .query(String.class)
                        .single())
                .contains("JobTenantException");
    }

    @Test
    void P10_aJobNamingAnyOtherBeanIsRefused() {
        LambdaTarget.TOUCHED.set(false);
        UUID job = new JobScheduler(storage)
                .<LambdaTarget>enqueue(UUID.randomUUID(), target -> target.touch())
                .asUUID();

        await().atMost(WAIT).until(() -> "FAILED".equals(jobState(job)));
        assertThat(LambdaTarget.TOUCHED).isFalse();
    }

    private UUID create(TestData.Member member) {
        return TenantScope.call(
                new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                () -> actions.create(new NewAction(
                                "test.scripted",
                                "worker:" + UUID.randomUUID(),
                                ActionMode.AUTOMATIC,
                                false,
                                null,
                                null,
                                null))
                        .action()
                        .id());
    }

    private String status(UUID action) {
        return admin.sql("select status from northstar.actions where id = :id")
                .param("id", action)
                .query(String.class)
                .single();
    }

    private String jobState(UUID job) {
        return admin.sql("select state from jobrunr.jobrunr_jobs where id = :id")
                .param("id", job.toString())
                .query(String.class)
                .optional()
                .orElse(null);
    }
}
