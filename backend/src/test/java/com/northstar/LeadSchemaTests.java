package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The lead tables' rules in the database (step 1.13, data-model.md sections 4, 6 and 13): published versions never
 * change, every reference stays inside its tenant, one open lead per contact, and the column checks.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LeadSchemaTests {

    private final PostgresAdmin postgres;
    private final JdbcClient admin;
    private final AppUserDatabase app;
    private final TestData data;

    LeadSchemaTests(@Autowired PostgresAdmin postgres, @Autowired AppUserDatabase app) {
        this.postgres = postgres;
        this.admin = postgres.jdbc();
        this.app = app;
        this.data = new TestData(postgres);
    }

    @Test
    void publishedVersionsCannotChangeForAnyRole() {
        TestData.Member owner = data.member("admin");
        UUID version = data.workflowVersion(owner.tenantId());

        assertThatThrownBy(() -> app.run(
                        owner.tenantId(),
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("update northstar.workflow_versions set config = '{}' where id = :id")
                                .param("id", version)
                                .update()))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.run(
                        owner.tenantId(),
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("delete from northstar.workflow_versions where id = :id")
                                .param("id", version)
                                .update()))
                .rootCause()
                .hasMessageContaining("permission denied");

        // Even the owner of the table, which bypasses RLS and has every privilege, is refused by the trigger.
        for (String statement : new String[] {
            "update northstar.workflow_versions set config = '{}' where id = '" + version + "'",
            "delete from northstar.workflow_versions where id = '" + version + "'",
            "truncate northstar.workflow_versions cascade"
        }) {
            assertThatThrownBy(() -> postgres.rolledBack(jdbc -> {
                        jdbc.sql("set local role migrator").update();
                        jdbc.sql(statement).update();
                    }))
                    .as(statement)
                    .rootCause()
                    .hasMessageContaining("Published workflow versions are immutable");
        }
        assertThat(admin.sql("select config::text from northstar.workflow_versions where id = :id")
                        .param("id", version)
                        .query(String.class)
                        .single())
                .contains("qualifying");
    }

    @Test
    void publishingANewVersionIsAnInsert() {
        TestData.Member owner = data.member("admin");
        UUID version = data.workflowVersion(owner.tenantId());
        UUID workflow = admin.sql("select workflow_id from northstar.workflow_versions where id = :id")
                .param("id", version)
                .query(UUID.class)
                .single();

        int inserted = app.as(
                owner.tenantId(),
                owner.userId(),
                "admin",
                jdbc -> jdbc.sql("""
                        insert into northstar.workflow_versions (tenant_id, workflow_id, version, config, published_by)
                        values (:t, :w, 2, '{"stages": []}', :by)""")
                        .param("t", owner.tenantId())
                        .param("w", workflow)
                        .param("by", owner.membershipId())
                        .update());

        assertThat(inserted).isOne();
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.workflow_versions (tenant_id, workflow_id, version, config)
                                values (:t, :w, 2, '{}')""")
                        .param("t", owner.tenantId())
                        .param("w", workflow)
                        .update())
                .as("version numbers are unique per workflow")
                .rootCause()
                .hasMessageContaining("workflow_versions_tenant_id_workflow_id_version_key");
    }

    @Test
    void versionsAndWorkflowsStayInsideTheirTenantAndWorkflow() {
        TestData.Member owner = data.member("admin");
        TestData.Member outsider = data.member("admin");
        UUID version = data.workflowVersion(owner.tenantId());
        UUID otherWorkflowsVersion = data.workflowVersion(owner.tenantId());
        UUID workflow = admin.sql("select workflow_id from northstar.workflow_versions where id = :id")
                .param("id", version)
                .query(UUID.class)
                .single();

        assertThatThrownBy(() -> admin.sql("update northstar.workflows set current_version_id = :v where id = :w")
                        .param("v", otherWorkflowsVersion)
                        .param("w", workflow)
                        .update())
                .as("a workflow's current version must be its own")
                .rootCause()
                .hasMessageContaining("workflows_current_version_fk");
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.workflow_versions (tenant_id, workflow_id, version, config, published_by)
                                values (:t, :w, 9, '{}', :by)""")
                        .param("t", owner.tenantId())
                        .param("w", workflow)
                        .param("by", outsider.membershipId())
                        .update())
                .as("published by a member of another business")
                .rootCause()
                .hasMessageContaining("foreign key");
    }

    @Test
    void anActionsLeadMustBeInTheSameTenant() {
        TestData.Member owner = data.member("admin");
        TestData.Member outsider = data.member("admin");
        UUID lead = data.lead(owner.tenantId(), data.workflowVersion(owner.tenantId()), null);
        UUID foreignLead = data.lead(outsider.tenantId(), data.workflowVersion(outsider.tenantId()), null);

        assertThat(insertAction(owner.tenantId(), lead)).isOne();
        assertThat(insertAction(owner.tenantId(), null))
                .as("actions without a lead")
                .isOne();
        assertThatThrownBy(() -> insertAction(owner.tenantId(), foreignLead))
                .rootCause()
                .hasMessageContaining("actions_lead_fk");
        assertThatThrownBy(() -> insertAction(owner.tenantId(), UUID.randomUUID()))
                .rootCause()
                .hasMessageContaining("actions_lead_fk");
    }

    @Test
    void aContactHasOneOpenLeadAtATime() {
        TestData.Member owner = data.member("admin");
        UUID version = data.workflowVersion(owner.tenantId());
        UUID contact = data.contact(owner.tenantId());
        UUID first = insertLead(owner.tenantId(), contact, version);

        assertThatThrownBy(() -> insertLead(owner.tenantId(), contact, version))
                .rootCause()
                .hasMessageContaining("leads_one_open_per_contact");

        admin.sql("update northstar.leads set stage = 'lost', closed_at = now() where id = :id")
                .param("id", first)
                .update();
        UUID second = insertLead(owner.tenantId(), contact, version); // W2: a closed lead frees the contact
        admin.sql("update northstar.leads set deleted_at = now() where id = :id")
                .param("id", second)
                .update();
        assertThat(insertLead(owner.tenantId(), contact, version)).isNotNull();
    }

    @Test
    void contactPhonesAreE164AndUniquePerTenant() {
        TestData.Member owner = data.member("admin");
        TestData.Member other = data.member("admin");

        insertContact(owner.tenantId(), "+919876543210");
        assertThatThrownBy(() -> insertContact(owner.tenantId(), "+919876543210"))
                .rootCause()
                .hasMessageContaining("contacts_tenant_id_normalized_phone_key");
        insertContact(other.tenantId(), "+919876543210"); // the same person can be a contact of two businesses
        for (String phone : new String[] {"9876543210", "+0123456789", "+91 98765 43210", "+91987654321012345"}) {
            assertThatThrownBy(() -> insertContact(owner.tenantId(), phone))
                    .as(phone)
                    .rootCause()
                    .hasMessageContaining("contacts_normalized_phone_check");
        }
        assertThatThrownBy(() -> admin.sql("update northstar.contacts set consent_state = 'maybe' where tenant_id = :t")
                        .param("t", owner.tenantId())
                        .update())
                .rootCause()
                .hasMessageContaining("contacts_consent_state_check");
    }

    @Test
    void leadColumnsAreChecked() {
        TestData.Member owner = data.member("admin");
        UUID lead = data.lead(owner.tenantId(), data.workflowVersion(owner.tenantId()), null);

        for (String[] invalid : new String[][] {
            {"score = 101", "leads_score_check"},
            {"score = -1", "leads_score_check"},
            {"band = 'lukewarm'", "leads_band_check"},
            {"origin = 'tv'", "leads_origin_check"}
        }) {
            assertThatThrownBy(() -> admin.sql("update northstar.leads set " + invalid[0] + " where id = :id")
                            .param("id", lead)
                            .update())
                    .as(invalid[0])
                    .rootCause()
                    .hasMessageContaining(invalid[1]);
        }
        assertThat(admin.sql("update northstar.leads set score = 100, band = 'hot' where id = :id")
                        .param("id", lead)
                        .update())
                .isOne();
    }

    // Helpers.

    private int insertAction(UUID tenant, UUID lead) {
        return admin.sql("""
                        insert into northstar.actions
                          (tenant_id, lead_id, type, status, idempotency_key, mode, next_attempt_at)
                        values (:t, :lead, 'test.fake', 'pending', :key, 'automatic', now())""")
                .param("t", tenant)
                .param("lead", lead)
                .param("key", "lead-fk:" + UUID.randomUUID())
                .update();
    }

    private UUID insertLead(UUID tenant, UUID contact, UUID version) {
        return admin.sql("""
                        insert into northstar.leads (tenant_id, contact_id, workflow_version_id, stage, origin)
                        values (:t, :contact, :version, 'qualifying', 'direct') returning id""")
                .param("t", tenant)
                .param("contact", contact)
                .param("version", version)
                .query(UUID.class)
                .single();
    }

    private void insertContact(UUID tenant, String phone) {
        admin.sql("insert into northstar.contacts (tenant_id, normalized_phone) values (:t, :phone)")
                .param("t", tenant)
                .param("phone", phone)
                .update();
    }
}
