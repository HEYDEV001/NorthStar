package com.northstar.devtools;

import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.TenantDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A published workflow for one business so inbound messages open leads before Phase 3 builds the real one (step 2.11).
 * A local-only development seed, never a migration and never a template: one workflow, named {@value #NAME} and with
 * template {@value #TEMPLATE}, whose one version holds the smallest config there can be, the stage New that every lead
 * starts in (workflow-spec.md section 3). It sets no rule, question, timer, price or message.
 *
 * <p>Running it again changes nothing. A business with any other published workflow is left alone, as two published
 * workflows would stop every lead from opening. Phase 3's config check (step 3.1) may refuse this config; the rows it
 * makes exist only in a local database.
 */
final class SeedDevWorkflow {

    static final String NAME = "DEV SEED (Phase 2 gate): stage New only";
    static final String TEMPLATE = "dev_seed";

    /** Every workflow config needs a "new" stage (step 2.4b); this is nothing but that. */
    static final String CONFIG = "{\"stages\": [{\"key\": \"new\", \"name\": \"New\"}]}";

    private static final String PUBLISHED = """
            select id, template, current_version_id from northstar.workflows
            where tenant_id = ? and current_version_id is not null
            for update""";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final TenantDirectory tenants;

    SeedDevWorkflow(JdbcClient jdbc, PlatformTransactionManager transactionManager, TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.tenants = tenants;
    }

    static SeedDevWorkflow on(ApplicationContext context) {
        return new SeedDevWorkflow(
                context.getBean(JdbcClient.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(TenantDirectory.class));
    }

    /** The seeded workflow's version, and whether this run made it. */
    record Seeded(UUID workflowId, UUID versionId, boolean created) {}

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
            return new Seeded(seeded.id(), seeded.versionId(), false);
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
        UUID version = jdbc.sql("""
                        insert into northstar.workflow_versions (tenant_id, workflow_id, version, config)
                        values (?, ?, 1, cast(? as jsonb)) returning id""")
                .param(tenant)
                .param(workflow)
                .param(CONFIG)
                .query(UUID.class)
                .single();
        jdbc.sql("update northstar.workflows set current_version_id = ? where tenant_id = ? and id = ?")
                .param(version)
                .param(tenant)
                .param(workflow)
                .update();
        return new Seeded(workflow, version, true);
    }
}
