package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.devtools.SeedDevWorkflow.Seeded;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.workflow.LeadIntake;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Phase 2 gate's development workflow (step 2.11): the one published version inbound messages need to open leads,
 * the stage New and nothing else, marked as a seed, made once, and never beside another published workflow. That it
 * refuses any database but a local one is GateToolTests and LocalOnlyTests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeedDevWorkflowTests {

    private final SeedDevWorkflow seed;
    private final LeadIntake leads;
    private final TransactionTemplate transactions;
    private final TestData data;
    private final JdbcClient admin;

    SeedDevWorkflowTests(
            @Autowired JdbcClient jdbc,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired TenantDirectory tenants,
            @Autowired LeadIntake leads,
            @Autowired PostgresAdmin postgres) {
        this.seed = new SeedDevWorkflow(jdbc, transactionManager, tenants);
        this.leads = leads;
        this.transactions = new TransactionTemplate(transactionManager);
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void itPublishesTheSmallestWorkflowThereIsMarkedAsASeed() {
        UUID tenant = data.tenant();
        UUID other = data.tenant();
        assertThat(published(tenant)).as("before: no lead can open").isEmpty();

        Seeded seeded = seed.seed(tenant);

        assertThat(seeded.created()).isTrue();
        assertThat(published(tenant))
                .as("the version the inbound job opens leads on")
                .contains(seeded.versionId());
        assertThat(admin.sql("select name || '|' || template || '|' || (draft_config is null) from northstar.workflows"
                                + " where id = :id and tenant_id = :t")
                        .param("id", seeded.workflowId())
                        .param("t", tenant)
                        .query(String.class)
                        .single())
                .isEqualTo("DEV SEED (Phase 2 gate): stage New only|dev_seed|true");
        assertThat(admin.sql("""
                        select version || '|' || (published_by is null) || '|'
                               || (config = '{"stages": [{"key": "new", "name": "New"}]}'::jsonb)
                        from northstar.workflow_versions where workflow_id = :id""")
                        .param("id", seeded.workflowId())
                        .query(String.class)
                        .list())
                .as("one version, its config the stage New and nothing else")
                .containsExactly("1|true|true");
        assertThat(published(other)).as("another business is untouched").isEmpty();
    }

    @Test
    void runningItAgainChangesNothing() {
        UUID tenant = data.tenant();
        Seeded first = seed.seed(tenant);

        Seeded again = seed.seed(tenant);

        assertThat(again.created()).isFalse();
        assertThat(again.workflowId()).isEqualTo(first.workflowId());
        assertThat(again.versionId()).isEqualTo(first.versionId());
        assertThat(workflows(tenant)).hasSize(1);
    }

    @Test
    void aBusinessWithAnotherPublishedWorkflowIsLeftAlone() {
        UUID tenant = data.tenant();
        UUID version = data.workflowVersion(tenant);

        assertRefused(tenant, "workflow_exists");

        assertThat(published(tenant)).contains(version);
        assertThat(workflows(tenant)).containsExactly("Shoot production");

        data.workflowVersion(tenant); // two published workflows: no lead can open, and the seed must not make it three
        assertRefused(tenant, "workflow_exists");
        assertThat(workflows(tenant)).hasSize(2);
    }

    @Test
    void anUnpublishedDraftDoesNotStopIt() {
        UUID tenant = data.tenant();
        admin.sql("insert into northstar.workflows (tenant_id, name, draft_config) values (:t, 'Draft', '{}')")
                .param("t", tenant)
                .update();

        assertThat(seed.seed(tenant).created()).isTrue();
        assertThat(workflows(tenant)).containsExactlyInAnyOrder("Draft", SeedDevWorkflow.NAME);
    }

    @Test
    void P9_anUnknownBusinessIsRefused() {
        assertRefused(UUID.randomUUID(), "unknown_tenant");
    }

    private void assertRefused(UUID tenant, String code) {
        assertThatThrownBy(() -> seed.seed(tenant))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private Optional<UUID> published(UUID tenant) {
        return SystemScope.call(tenant, () -> transactions.execute(status -> leads.publishedWorkflowVersion()));
    }

    private List<String> workflows(UUID tenant) {
        return admin.sql("select name from northstar.workflows where tenant_id = :t")
                .param("t", tenant)
                .query(String.class)
                .list();
    }
}
