package com.northstar.devtools;

import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.workflow.Workflows;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A published workflow for one business so inbound messages open leads before Phase 3 builds the real one (step 2.11).
 * A local-only development seed, never a migration and never a template: one workflow, named {@value #NAME} and with
 * template {@value #TEMPLATE}, whose config is the smallest format v1 accepts (workflow-spec.md section 15, step 3.1):
 * the stage New that every lead starts in and the stage Needs Human the engine can send any lead to. It sets no rule,
 * question, timer, price or message.
 *
 * <p>The seed creates its workflow row itself (its template is not an industry template, so {@link Workflows#create}
 * would refuse it), then saves the config as the draft and publishes it through {@link Workflows}, as the system actor,
 * so it passes the same check as every other workflow. Running it again changes nothing. A business whose seed version
 * holds another config, such as one seeded before step 3.1 with the stage New only, gets this config published as the
 * seed's next version; leads already open keep theirs (W36). A business with any other published workflow is left
 * alone, as two published workflows would stop every lead from opening.
 */
final class SeedDevWorkflow {

    static final String NAME = "DEV SEED (Phase 2 gate): stages New and Needs Human";
    static final String TEMPLATE = "dev_seed";

    /** The stage every lead starts in (step 2.4b) and the one every config needs beside it (step 3.1); nothing else. */
    static final String CONFIG =
            "{\"schema_version\": 1, \"stages\": [{\"key\": \"new\", \"name\": \"New\"}, {\"key\": \"needs_human\"}]}";

    private static final String PUBLISHED = """
            select id, template, current_version_id from northstar.workflows
            where tenant_id = ? and current_version_id is not null""";

    // Publishing writes the workflow's ID and the version number into the document; the rest must be this config.
    private static final String IS_THIS_CONFIG = """
            select (config - 'workflow_id' - 'version') = cast(? as jsonb) from northstar.workflow_versions
            where tenant_id = ? and id = ?""";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final TenantDirectory tenants;
    private final Workflows workflows;

    SeedDevWorkflow(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            TenantDirectory tenants,
            Workflows workflows) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.tenants = tenants;
        this.workflows = workflows;
    }

    static SeedDevWorkflow on(ApplicationContext context) {
        return new SeedDevWorkflow(
                context.getBean(JdbcClient.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(TenantDirectory.class),
                context.getBean(Workflows.class));
    }

    /** What a run did. */
    enum Outcome {
        CREATED("created"),
        REPUBLISHED("republished"),
        ALREADY_THERE("already_there");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /** The seeded workflow's current version, and what this run did. */
    record Seeded(UUID workflowId, UUID versionId, Outcome outcome) {}

    private record Published(UUID id, String template, UUID versionId) {}

    Seeded seed(UUID tenant) {
        return SystemScope.call(tenant, () -> {
            if (!tenants.currentTenantExists()) {
                throw new LocalToolException("unknown_tenant", "No business has this ID. Nothing was done.");
            }
            return transactions.execute(status -> seedIn(tenant));
        });
    }

    private Seeded seedIn(UUID tenant) {
        List<Published> published = jdbc.sql(PUBLISHED)
                .param(tenant)
                .query((row, n) -> new Published(
                        row.getObject("id", UUID.class),
                        row.getString("template"),
                        row.getObject("current_version_id", UUID.class)))
                .list();
        if (published.size() == 1 && TEMPLATE.equals(published.getFirst().template())) {
            Published seeded = published.getFirst();
            boolean current = Boolean.TRUE.equals(jdbc.sql(IS_THIS_CONFIG)
                    .param(CONFIG)
                    .param(tenant)
                    .param(seeded.versionId())
                    .query(Boolean.class)
                    .single());
            if (current) return new Seeded(seeded.id(), seeded.versionId(), Outcome.ALREADY_THERE);
            return new Seeded(seeded.id(), publish(seeded.id()), Outcome.REPUBLISHED);
        }
        if (!published.isEmpty()) {
            throw new LocalToolException(
                    "workflow_exists",
                    "This business already has a published workflow; it is left as it is. Nothing was done.");
        }
        UUID workflow = jdbc.sql("""
                        insert into northstar.workflows (tenant_id, name, template) values (?, ?, ?) returning id""")
                .param(tenant)
                .param(NAME)
                .param(TEMPLATE)
                .query(UUID.class)
                .single();
        return new Seeded(workflow, publish(workflow), Outcome.CREATED);
    }

    private UUID publish(UUID workflow) {
        Workflows.DraftSaved draft = workflows.saveDraft(workflow, CONFIG);
        if (!draft.warnings().isEmpty()) {
            throw new IllegalStateException("The seed's config does not pass the config check: " + draft.warnings());
        }
        return workflows.publish(workflow, draft.draftHash()).id();
    }
}
