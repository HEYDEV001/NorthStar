package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.devtools.SeedDevWorkflow.Outcome;
import com.northstar.devtools.SeedDevWorkflow.Seeded;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.workflow.LeadIntake;
import com.northstar.workflow.Workflows;
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
 * the stages New and Needs Human and nothing else (the smallest config format v1 accepts, step 3.1), published through
 * the workflow service, marked as a seed, made once, brought up to date once, and never beside another published
 * workflow. That it refuses any database but a local one is GateToolTests and LocalOnlyTests.
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
            @Autowired Workflows workflows,
            @Autowired LeadIntake leads,
            @Autowired PostgresAdmin postgres) {
        this.seed = new SeedDevWorkflow(jdbc, transactionManager, tenants, workflows);
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

        assertThat(seeded.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(published(tenant))
                .as("the version the inbound job opens leads on")
                .contains(seeded.versionId());
        assertThat(admin.sql("""
                        select name || '|' || template || '|' || (draft_config = cast(:config as jsonb))
                        from northstar.workflows where id = :id and tenant_id = :t""")
                        .param("config", SeedDevWorkflow.CONFIG)
                        .param("id", seeded.workflowId())
                        .param("t", tenant)
                        .query(String.class)
                        .single())
                .as("published from its draft, which stays")
                .isEqualTo("DEV SEED (Phase 2 gate): stages New and Needs Human|dev_seed|true");
        assertThat(admin.sql("""
                        select version || '|' || (published_by is null) || '|'
                               || (config = cast(:config as jsonb))
                        from northstar.workflow_versions where workflow_id = :id""")
                        .param("config", TestData.minimalConfig(seeded.workflowId(), 1))
                        .param("id", seeded.workflowId())
                        .query(String.class)
                        .list())
                .as("one version by the system actor: New and Needs Human, the workflow ID and version written in")
                .containsExactly("1|true|true");
        assertThat(admin.sql("select count(*) from northstar.audit_log where tenant_id = :t and action = :a")
                        .param("t", tenant)
                        .param("a", "workflow.published")
                        .query(Long.class)
                        .single())
                .as("published like any workflow")
                .isOne();
        assertThat(published(other)).as("another business is untouched").isEmpty();
    }

    @Test
    void runningItAgainChangesNothing() {
        UUID tenant = data.tenant();
        Seeded first = seed.seed(tenant);

        Seeded again = seed.seed(tenant);

        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_THERE);
        assertThat(again.workflowId()).isEqualTo(first.workflowId());
        assertThat(again.versionId()).isEqualTo(first.versionId());
        assertThat(workflows(tenant)).hasSize(1);
    }

    @Test
    void W36_aSeedVersionWithAnotherConfigGetsThisOneAsItsNextVersionAndItsLeadsKeepTheirs() {
        UUID tenant = data.tenant();
        // A seed as an earlier build left it: its one version holds another config (before step 3.1, the stage New
        // only; written here in today's envelope, as the database now checks every new version's).
        UUID workflow = admin.sql("""
                        insert into northstar.workflows (tenant_id, name, template)
                        values (:t, 'DEV SEED (Phase 2 gate): stage New only', 'dev_seed') returning id""").param("t", tenant).query(UUID.class).single();
        UUID old = admin.sql("""
                        insert into northstar.workflow_versions (tenant_id, workflow_id, version, config)
                        values (:t, :w, 1, cast(:config as jsonb)) returning id""")
                .param("t", tenant)
                .param("w", workflow)
                .param(
                        "config",
                        "{\"schema_version\": 1, \"workflow_id\": \"" + workflow
                                + "\", \"version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\"}]}")
                .query(UUID.class)
                .single();
        admin.sql("update northstar.workflows set current_version_id = :v where id = :w")
                .param("v", old)
                .param("w", workflow)
                .update();
        UUID lead = data.lead(tenant, old, null);

        Seeded seeded = seed.seed(tenant);

        assertThat(seeded.outcome()).isEqualTo(Outcome.REPUBLISHED);
        assertThat(seeded.workflowId()).isEqualTo(workflow);
        assertThat(seeded.versionId()).isNotEqualTo(old);
        assertThat(published(tenant)).as("new leads open on version 2").contains(seeded.versionId());
        assertThat(admin.sql("select workflow_version_id from northstar.leads where id = :l")
                        .param("l", lead)
                        .query(UUID.class)
                        .single())
                .as("the open lead keeps version 1")
                .isEqualTo(old);
        assertThat(admin.sql("select name from northstar.workflows where id = :w")
                        .param("w", workflow)
                        .query(String.class)
                        .single())
                .as("the old seed keeps its name")
                .isEqualTo("DEV SEED (Phase 2 gate): stage New only");
        assertThat(seed.seed(tenant).outcome()).as("and once is enough").isEqualTo(Outcome.ALREADY_THERE);
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

        assertThat(seed.seed(tenant).outcome()).isEqualTo(Outcome.CREATED);
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
