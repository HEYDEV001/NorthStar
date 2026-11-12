package com.northstar.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.LogContext;
import com.northstar.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Who sees which leads (step 1.13, P11, P9), through the real app_user login with RLS: staff only their assigned leads,
 * admins and the system actor the whole tenant, nobody another tenant's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LeadIsolationTests {

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;
    private final MockMvcTester mvc;
    private final TestTokens tokens;

    LeadIsolationTests(
            @Autowired AppUserDatabase app,
            @Autowired PostgresAdmin postgres,
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
        this.mvc = mvc;
        this.tokens = tokens;
    }

    /** A business with an admin, two staff members and one lead each, plus an unassigned lead. */
    private record Shop(
            UUID tenant,
            TestData.Member owner,
            UUID staffUser,
            UUID staffMembership,
            UUID colleagueMembership,
            UUID version,
            UUID mine,
            UUID theirs,
            UUID unassigned) {}

    private Shop shop() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID colleague = data.membership(owner.tenantId(), data.user(), "staff", "active");
        UUID version = data.workflowVersion(owner.tenantId());
        return new Shop(
                owner.tenantId(),
                owner,
                staffUser,
                staffMembership,
                colleague,
                version,
                data.lead(owner.tenantId(), version, staffMembership),
                data.lead(owner.tenantId(), version, colleague),
                data.lead(owner.tenantId(), version, null));
    }

    @Test
    void P11_staffSeeOnlyTheirAssignedLeads() {
        Shop shop = shop();
        UUID staff = shop.staffUser();

        assertThat(leadsSeenBy(shop.tenant(), staff, "staff")).containsExactly(shop.mine());
        assertThat(updateAs(shop.tenant(), staff, "staff", "stage = 'quoted'", shop.mine()))
                .isOne();
        assertThat(updateAs(shop.tenant(), staff, "staff", "stage = 'quoted'", shop.theirs()))
                .as("a colleague's lead is not there to change")
                .isZero();
        assertThat(updateAs(
                        shop.tenant(),
                        staff,
                        "staff",
                        "assigned_membership_id = '" + shop.staffMembership() + "'",
                        shop.theirs()))
                .as("taking a colleague's lead")
                .isZero();
        assertThat(updateAs(shop.tenant(), staff, "staff", "assigned_membership_id = null", shop.unassigned()))
                .isZero();

        assertThatThrownBy(() -> updateAs(
                        shop.tenant(),
                        staff,
                        "staff",
                        "assigned_membership_id = '" + shop.colleagueMembership() + "'",
                        shop.mine()))
                .as("handing their own lead to someone else")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> insertAs(shop, staff, "staff", null))
                .as("creating an unassigned lead")
                .rootCause()
                .hasMessageContaining("row-level security");
        assertThat(insertAs(shop, staff, "staff", shop.staffMembership())).isOne();
        assertThatThrownBy(() -> app.run(
                        shop.tenant(),
                        staff,
                        "staff",
                        jdbc -> jdbc.sql("delete from northstar.leads where id = :id")
                                .param("id", shop.mine())
                                .update()))
                .rootCause()
                .hasMessageContaining("permission denied");

        assertThat(stageOf(shop.theirs())).isEqualTo("qualifying");
        assertThat(assigneeOf(shop.theirs())).isEqualTo(shop.colleagueMembership());
        assertThat(assigneeOf(shop.mine())).isEqualTo(shop.staffMembership());
    }

    @Test
    void adminsSeeEveryLeadInTheirTenant() {
        Shop shop = shop();
        Shop other = shop();
        UUID owner = shop.owner().userId();

        assertThat(leadsSeenBy(shop.tenant(), owner, "admin"))
                .containsExactlyInAnyOrder(shop.mine(), shop.theirs(), shop.unassigned());
        assertThat(updateAs(
                        shop.tenant(),
                        owner,
                        "admin",
                        "assigned_membership_id = '" + shop.staffMembership() + "'",
                        shop.unassigned()))
                .isOne();
        assertThat(updateAs(shop.tenant(), owner, "admin", "stage = 'quoted'", other.mine()))
                .as("another tenant's lead")
                .isZero();
    }

    @Test
    void theSystemActorSeesTheTenantsLeadsAndNoOthers() {
        Shop shop = shop();
        Shop other = shop();
        UUID system = TenantContext.SYSTEM_USER_ID;

        assertThat(leadsSeenBy(shop.tenant(), system, "system"))
                .containsExactlyInAnyOrder(shop.mine(), shop.theirs(), shop.unassigned());
        assertThat(updateAs(shop.tenant(), system, "system", "stage = 'scored'", shop.unassigned()))
                .isOne();
        assertThat(updateAs(shop.tenant(), system, "system", "stage = 'scored'", other.mine()))
                .isZero();
    }

    @Test
    void theStaffPolicyFailsClosed() {
        Shop shop = shop();

        for (String role : List.of("", "owner", "Admin")) {
            assertThat(leadsSeenBy(shop.tenant(), shop.owner().userId(), role))
                    .as("role '%s'", role)
                    .isEmpty();
        }
        admin.sql("update northstar.memberships set status = 'deactivated' where id = :id")
                .param("id", shop.staffMembership())
                .update();
        assertThat(leadsSeenBy(shop.tenant(), shop.staffUser(), "staff"))
                .as("a deactivated member")
                .isEmpty();
    }

    @Test
    void P9_leadFromAnotherTenantIs404() throws Exception {
        Shop shop = shop();
        Shop other = shop();

        MvcTestResult own = getLead(shop.owner().userId(), shop.mine());
        MvcTestResult foreign = getLead(shop.owner().userId(), other.mine());
        MvcTestResult missing = getLead(shop.owner().userId(), UUID.randomUUID());

        assertThat(own).hasStatusOk().bodyJson().extractingPath("$.stage").isEqualTo("qualifying");
        assertThat(foreign).hasStatus(404);
        assertThat(foreign.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString());
    }

    @Test
    void P11_staffGet404ForAColleaguesLeadThroughTheApi() {
        Shop shop = shop();

        assertThat(getLead(shop.staffUser(), shop.mine())).hasStatusOk();
        assertThat(getLead(shop.staffUser(), shop.theirs())).hasStatus(404);
        assertThat(getLead(shop.staffUser(), shop.unassigned())).hasStatus(404);
    }

    // Helpers.

    private List<UUID> leadsSeenBy(UUID tenant, UUID user, String role) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("select id from northstar.leads")
                        .query(UUID.class)
                        .list());
    }

    private int updateAs(UUID tenant, UUID user, String role, String set, UUID lead) {
        Integer changed = app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("update northstar.leads set " + set + " where id = :id")
                        .param("id", lead)
                        .update());
        return changed;
    }

    private int insertAs(Shop shop, UUID user, String role, UUID assigned) {
        UUID contact = data.contact(shop.tenant());
        Integer inserted = app.as(
                shop.tenant(),
                user,
                role,
                jdbc -> jdbc.sql("""
                        insert into northstar.leads
                          (tenant_id, contact_id, workflow_version_id, stage, origin, assigned_membership_id)
                        values (:t, :contact, :version, 'qualifying', 'manual', :assigned)""")
                        .param("t", shop.tenant())
                        .param("contact", contact)
                        .param("version", shop.version())
                        .param("assigned", assigned)
                        .update());
        return inserted;
    }

    private String stageOf(UUID lead) {
        return admin.sql("select stage from northstar.leads where id = :id")
                .param("id", lead)
                .query(String.class)
                .single();
    }

    private UUID assigneeOf(UUID lead) {
        return admin.sql("select assigned_membership_id from northstar.leads where id = :id")
                .param("id", lead)
                .query(UUID.class)
                .single();
    }

    private MvcTestResult getLead(UUID user, UUID lead) {
        return mvc.get()
                .uri("/api/v1/test/leads/" + lead)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, "p9-lead")
                .exchange();
    }
}
