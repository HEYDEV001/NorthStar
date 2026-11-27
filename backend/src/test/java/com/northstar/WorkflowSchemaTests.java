package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the database itself holds about workflows since step 3.1 (data-model.md sections 6 and 12, workflow-spec.md
 * section 16), whatever path a write takes: published versions never change for any role, the owner included; a
 * workflow's current version only moves to its newest; a lead keeps its version (W36); only admins and the system actor
 * write workflows, a version published as yourself; and the documents' shape.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WorkflowSchemaTests {

    private static final UUID SYSTEM_USER = new UUID(0, 0);

    private final AppUserDatabase app;
    private final PostgresAdmin postgres;
    private final TestData data;
    private final JdbcClient admin;

    WorkflowSchemaTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.postgres = postgres;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    /** A business with an admin, a staff member, and a workflow published once (version 1). */
    private record Shop(TestData.Member owner, UUID staffUser, UUID staffMembership, UUID workflow, UUID version) {

        UUID tenant() {
            return owner.tenantId();
        }
    }

    private Shop shop() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID version = data.workflowVersion(owner.tenantId());
        return new Shop(owner, staffUser, staffMembership, workflowOf(version), version);
    }

    @Test
    void publishedVersionsNeverChangeForAnyRoleTheOwnerIncluded() {
        Shop shop = shop();
        List<String> statements = List.of(
                "update northstar.workflow_versions set config = config || '{\"x\": 1}' where id = '" + shop.version()
                        + "'",
                "update northstar.workflow_versions set published_at = now() where id = '" + shop.version() + "'",
                "delete from northstar.workflow_versions where id = '" + shop.version() + "'",
                "truncate northstar.workflow_versions cascade");

        for (String role : List.of("admin", "staff", "system")) {
            UUID user = role.equals("admin")
                    ? shop.owner().userId()
                    : role.equals("staff") ? shop.staffUser() : SYSTEM_USER;
            for (String statement : statements) {
                assertThatThrownBy(() -> app.run(
                                shop.tenant(),
                                user,
                                role,
                                jdbc -> jdbc.sql(statement).update()))
                        .as("app_user as " + role + ": " + statement)
                        .rootCause()
                        .hasMessageContaining("permission denied");
            }
        }
        for (String role : List.of("app_admin", "jobrunr_user", "secrets_user")) {
            for (String statement : statements) {
                assertThatThrownBy(() -> postgres.rolledBack(jdbc -> {
                            jdbc.sql("set local role " + role).update();
                            jdbc.sql(statement).update();
                        }))
                        .as(role + ": " + statement)
                        .rootCause()
                        .hasMessageContaining("permission denied");
            }
        }
        // The owner has every privilege and bypasses RLS: the triggers (NSW01) refuse it, and so does truncating the
        // workflows, which would take their versions with them.
        for (String statement : concat(statements, "truncate northstar.workflows cascade")) {
            assertThatThrownBy(() -> postgres.rolledBack(jdbc -> {
                        jdbc.sql("set local role migrator").update();
                        jdbc.sql(statement).update();
                    }))
                    .as("migrator: " + statement)
                    .rootCause()
                    .hasMessageContaining("Published workflow versions are immutable");
        }

        assertThat(admin.sql("select config = cast(:c as jsonb) from northstar.workflow_versions where id = :id")
                        .param("c", TestData.minimalConfig(shop.workflow(), 1))
                        .param("id", shop.version())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void aPublishedWorkflowIsNeverDeleted() {
        Shop shop = shop();

        assertThatThrownBy(() -> app.run(
                        shop.tenant(),
                        shop.owner().userId(),
                        "admin",
                        jdbc -> jdbc.sql("delete from northstar.workflows where id = :w")
                                .param("w", shop.workflow())
                                .update()))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> postgres.rolledBack(jdbc -> {
                    jdbc.sql("set local role migrator").update();
                    jdbc.sql("delete from northstar.workflows where id = '" + shop.workflow() + "'")
                            .update();
                }))
                .as("its versions hold it, even for the owner")
                .rootCause()
                .hasMessageContaining("foreign key");
    }

    @Test
    void theCurrentVersionOnlyMovesToTheNewest() {
        Shop shop = shop();
        UUID second = insertVersion(shop.tenant(), shop.workflow(), 2);

        assertThat(asAdmin(
                        shop,
                        "update northstar.workflows set current_version_id = '" + second + "' where id = '"
                                + shop.workflow() + "'"))
                .as("publishing: to the newest")
                .isOne();
        assertThat(asAdmin(
                        shop,
                        "update northstar.workflows set name = 'Renamed', current_version_id = current_version_id"
                                + " where id = '" + shop.workflow() + "'"))
                .as("unchanged is fine")
                .isOne();

        String back = "update northstar.workflows set current_version_id = '" + shop.version() + "' where id = '"
                + shop.workflow() + "'";
        String unpublish =
                "update northstar.workflows set current_version_id = null where id = '" + shop.workflow() + "'";
        assertThatThrownBy(() -> asAdmin(shop, back))
                .rootCause()
                .hasMessageContaining("can only move to its newest version");
        assertThatThrownBy(() -> asAdmin(shop, unpublish)).rootCause().hasMessageContaining("keeps a current version");
        for (String statement : List.of(back, unpublish)) {
            assertThatThrownBy(() -> asMigrator(statement))
                    .as("migrator: " + statement)
                    .rootCause()
                    .hasMessageContaining("current version");
        }
        assertThat(currentVersion(shop.workflow())).isEqualTo(second);
    }

    @Test
    void W36_aLeadNeverChangesVersionForAnyRole() {
        Shop shop = shop();
        UUID second = insertVersion(shop.tenant(), shop.workflow(), 2);
        UUID lead = data.lead(shop.tenant(), shop.version(), shop.staffMembership());
        String move = "update northstar.leads set workflow_version_id = '" + second + "' where id = '" + lead + "'";

        assertThatThrownBy(() -> asAdmin(shop, move))
                .rootCause()
                .hasMessageContaining("keeps the workflow version it started on");
        assertThatThrownBy(() -> app.run(
                        shop.tenant(),
                        shop.staffUser(),
                        "staff",
                        jdbc -> jdbc.sql(move).update()))
                .as("the assigned staff member")
                .rootCause()
                .hasMessageContaining("keeps the workflow version it started on");
        assertThatThrownBy(() -> app.run(
                        shop.tenant(),
                        SYSTEM_USER,
                        "system",
                        jdbc -> jdbc.sql(move).update()))
                .rootCause()
                .hasMessageContaining("keeps the workflow version it started on");
        assertThatThrownBy(() -> asMigrator(move))
                .as("the owner too")
                .rootCause()
                .hasMessageContaining("keeps the workflow version it started on");

        assertThat(asAdmin(
                        shop,
                        "update northstar.leads set stage = 'scored', workflow_version_id = workflow_version_id"
                                + " where id = '" + lead + "'"))
                .as("the rest of the lead changes as before")
                .isOne();
        assertThat(admin.sql("select workflow_version_id from northstar.leads where id = :l")
                        .param("l", lead)
                        .query(UUID.class)
                        .single())
                .isEqualTo(shop.version());
    }

    @Test
    void onlyAdminsAndTheSystemActorWriteWorkflows() {
        Shop shop = shop();
        String insert = "insert into northstar.workflows (tenant_id, name, template) values ('" + shop.tenant()
                + "', 'Another', 'blank')";
        String saveDraft = "update northstar.workflows set draft_config = '{}' where id = '" + shop.workflow() + "'";

        assertThatThrownBy(() -> app.run(
                        shop.tenant(),
                        shop.staffUser(),
                        "staff",
                        jdbc -> jdbc.sql(insert).update()))
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(app.<Integer>as(
                        shop.tenant(),
                        shop.staffUser(),
                        "staff",
                        jdbc -> jdbc.sql(saveDraft).update()))
                .as("staff find nothing to change")
                .isZero();
        assertThatThrownBy(() -> app.run(
                        shop.tenant(),
                        SYSTEM_USER,
                        "webhook",
                        jdbc -> jdbc.sql(insert).update()))
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(app.<Long>as(
                        shop.tenant(),
                        shop.staffUser(),
                        "staff",
                        jdbc -> jdbc.sql("select count(*) from northstar.workflows where id = :w")
                                .param("w", shop.workflow())
                                .query(Long.class)
                                .single()))
                .as("but read them")
                .isOne();

        assertThat(asAdmin(shop, insert)).isOne();
        assertThat(asAdmin(shop, saveDraft)).isOne();
        assertThat(app.<Integer>as(
                        shop.tenant(),
                        SYSTEM_USER,
                        "system",
                        jdbc -> jdbc.sql(saveDraft).update()))
                .isOne();
        assertThatThrownBy(() -> asAdmin(
                        shop,
                        "update northstar.workflows set template = 'agency' where id = '" + shop.workflow() + "'"))
                .as("the template is written once")
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void aVersionIsPublishedAsYourselfOrByTheSystemAsNobody() {
        Shop shop = shop();
        UUID colleague = data.membership(shop.tenant(), data.user(), "admin", "active");
        UUID workflow =
                admin.sql("""
                        insert into northstar.workflows (tenant_id, name, template)
                        values (:t, 'Unpublished', 'blank') returning id""").param("t", shop.tenant()).query(UUID.class).single();

        assertThatThrownBy(() -> publishAs(shop, shop.owner().userId(), "admin", workflow, 1, null))
                .as("an admin is recorded")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> publishAs(shop, shop.owner().userId(), "admin", workflow, 1, colleague))
                .as("as someone else")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> publishAs(shop, shop.staffUser(), "staff", workflow, 1, shop.staffMembership()))
                .as("staff never publish")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> publishAs(
                        shop, SYSTEM_USER, "system", workflow, 1, shop.owner().membershipId()))
                .as("the system actor is nobody")
                .rootCause()
                .hasMessageContaining("row-level security");

        assertThat(publishAs(
                        shop,
                        shop.owner().userId(),
                        "admin",
                        workflow,
                        1,
                        shop.owner().membershipId()))
                .isOne();
        assertThat(publishAs(shop, SYSTEM_USER, "system", workflow, 2, null)).isOne();
    }

    @Test
    void aVersionStatesItsFormatAndCarriesItsOwnWorkflowIdAndNumber() {
        Shop shop = shop();
        String good = TestData.minimalConfig(shop.workflow(), 2);

        for (String config : List.of(
                "{\"stages\": []}",
                good.replace("\"schema_version\": 1", "\"schema_version\": \"1\""),
                good.replace("\"schema_version\": 1", "\"schema_version\": 1.5"),
                good.replace("\"version\": 2", "\"version\": 3"),
                good.replace(shop.workflow().toString(), UUID.randomUUID().toString()),
                good.replace(", \"version\": 2", ""),
                "[]")) {
            assertThatThrownBy(() -> insertVersion(shop.tenant(), shop.workflow(), 2, config))
                    .as(config)
                    .rootCause()
                    .hasMessageContaining("workflow_versions_config_shape");
        }
        assertThat(insertVersion(shop.tenant(), shop.workflow(), 2, good)).isNotNull();
    }

    @Test
    void aDraftIsAJsonObjectOfAtMostOneMebibyte() {
        Shop shop = shop();

        for (String draft :
                List.of("'[]'::jsonb", "'\"text\"'::jsonb", "jsonb_build_object('pad', repeat('a', 1048576))")) {
            assertThatThrownBy(
                            () -> admin.sql("update northstar.workflows set draft_config = " + draft + " where id = :w")
                                    .param("w", shop.workflow())
                                    .update())
                    .as(draft)
                    .rootCause()
                    .hasMessageContaining("workflows_draft_config_shape");
        }
        assertThat(admin.sql("update northstar.workflows set draft_config = '{}' where id = :w")
                        .param("w", shop.workflow())
                        .update())
                .isOne();
        assertThat(admin.sql("update northstar.workflows set draft_config = null where id = :w")
                        .param("w", shop.workflow())
                        .update())
                .isOne();
    }

    // Helpers.

    private int asAdmin(Shop shop, String statement) {
        return app.as(
                shop.tenant(),
                shop.owner().userId(),
                "admin",
                jdbc -> jdbc.sql(statement).update());
    }

    private void asMigrator(String statement) {
        postgres.rolledBack(jdbc -> {
            jdbc.sql("set local role migrator").update();
            jdbc.sql(statement).update();
        });
    }

    private int publishAs(Shop shop, UUID user, String role, UUID workflow, int version, UUID publishedBy) {
        return app.as(
                shop.tenant(),
                user,
                role,
                jdbc -> jdbc.sql("""
                        insert into northstar.workflow_versions (tenant_id, workflow_id, version, config, published_by)
                        values (:t, :w, :v, cast(:c as jsonb), :by)""")
                        .param("t", shop.tenant())
                        .param("w", workflow)
                        .param("v", version)
                        .param("c", TestData.minimalConfig(workflow, version))
                        .param("by", publishedBy)
                        .update());
    }

    private UUID insertVersion(UUID tenant, UUID workflow, int version) {
        return insertVersion(tenant, workflow, version, TestData.minimalConfig(workflow, version));
    }

    private UUID insertVersion(UUID tenant, UUID workflow, int version, String config) {
        return admin.sql("""
                        insert into northstar.workflow_versions (tenant_id, workflow_id, version, config)
                        values (:t, :w, :v, cast(:c as jsonb)) returning id""")
                .param("t", tenant)
                .param("w", workflow)
                .param("v", version)
                .param("c", config)
                .query(UUID.class)
                .single();
    }

    private UUID workflowOf(UUID version) {
        return admin.sql("select workflow_id from northstar.workflow_versions where id = :v")
                .param("v", version)
                .query(UUID.class)
                .single();
    }

    private UUID currentVersion(UUID workflow) {
        return admin.sql("select current_version_id from northstar.workflows where id = :w")
                .param("w", workflow)
                .query(UUID.class)
                .single();
    }

    private static List<String> concat(List<String> list, String more) {
        return java.util.stream.Stream.concat(list.stream(), java.util.stream.Stream.of(more))
                .toList();
    }
}
