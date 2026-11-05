package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The tenancy tables (data-model.md section 3) as app_user sees them: RLS, column privileges, the one-active-membership
 * rule (D4), composite tenant foreign keys, and the insert-only audit log. Fixtures are written as postgres, which
 * bypasses RLS; every assertion runs through the real app_user login.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenancySchemaTests {

    private static final String INSUFFICIENT_PRIVILEGE = "42501"; // also raised for RLS with check violations
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    private final JdbcClient postgres;
    private final AppUserDatabase app;

    TenancySchemaTests(@Autowired PostgresAdmin postgres, @Autowired AppUserDatabase app) {
        this.postgres = postgres.jdbc();
        this.app = app;
    }

    @Nested
    class Tenants {

        @Test
        void appUserSeesOnlyItsOwnTenant() {
            UUID a = tenant();
            UUID b = tenant();
            List<UUID> visible = app.as(
                    a,
                    user(),
                    "staff",
                    jdbc -> jdbc.sql("select id from northstar.tenants")
                            .query(UUID.class)
                            .list());
            assertThat(visible).containsExactly(a).doesNotContain(b);
        }

        @Test
        void onlyAdminsCanChangeSettings() {
            UUID tenant = tenant();
            UUID user = user();

            int staffUpdates = app.as(tenant, user, "staff", jdbc -> renameTenant(jdbc, tenant, "By staff"));
            int adminUpdates = app.as(tenant, user, "admin", jdbc -> renameTenant(jdbc, tenant, "By admin"));

            assertThat(staffUpdates).isZero();
            assertThat(adminUpdates).isOne();
            assertThat(tenantColumn(tenant, "name")).isEqualTo("By admin");
        }

        @Test
        void adminCannotChangeStatusPlanActivationOrSlug() {
            UUID tenant = tenant();
            for (String change : new String[] {
                "status = 'active'", "plan_id = gen_random_uuid()", "activated_at = now()", "slug = 'renamed'"
            }) {
                assertFails(
                        INSUFFICIENT_PRIVILEGE,
                        () -> app.run(
                                tenant,
                                user(),
                                "admin",
                                jdbc -> jdbc.sql("update northstar.tenants set " + change + " where id = :id")
                                        .param("id", tenant)
                                        .update()));
            }
        }

        @Test
        void newTenantIsCreatedOnlyForTheContextTenantAndStartsOnboarding() {
            UUID tenant = UUID.randomUUID();
            UUID user = user();

            app.run(tenant, user, "admin", jdbc -> insertTenant(jdbc, tenant));

            assertThat(tenantColumn(tenant, "status")).isEqualTo("onboarding");
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(tenant(), user, "admin", jdbc -> insertTenant(jdbc, UUID.randomUUID())));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(
                            tenant,
                            user,
                            "admin",
                            jdbc -> jdbc.sql("""
                                    insert into northstar.tenants (id, name, slug, status)
                                    values (gen_random_uuid(), 'Active', :slug, 'active')""")
                                    .param("slug", "slug-" + UUID.randomUUID())
                                    .update()));
        }

        @Test
        void updatedAtIsSetOnUpdate() {
            UUID tenant = tenant();
            assertThat(tenantColumn(tenant, "updated_at")).isNull();

            app.run(tenant, user(), "admin", jdbc -> renameTenant(jdbc, tenant, "Renamed"));

            assertThat(tenantColumn(tenant, "updated_at")).isNotNull();
        }

        private int renameTenant(JdbcClient jdbc, UUID tenant, String name) {
            return jdbc.sql("update northstar.tenants set name = :name where id = :id")
                    .param("name", name)
                    .param("id", tenant)
                    .update();
        }

        private int insertTenant(JdbcClient jdbc, UUID tenant) {
            return jdbc.sql("insert into northstar.tenants (id, name, slug) values (:id, 'New business', :slug)")
                    .param("id", tenant)
                    .param("slug", "slug-" + tenant)
                    .update();
        }
    }

    @Nested
    class UserProfiles {

        @Test
        void visibleProfilesAreYourOwnAndCurrentTenantMembers() {
            UUID tenant = tenant();
            UUID me = user();
            UUID colleague = user();
            UUID otherTenantMember = user();
            UUID stranger = user();
            membership(tenant, me, "admin", "active");
            membership(tenant, colleague, "staff", "active");
            membership(tenant(), otherTenantMember, "staff", "active");

            List<UUID> visible = app.as(
                    tenant,
                    me,
                    "admin",
                    jdbc -> jdbc.sql("select id from northstar.user_profiles")
                            .query(UUID.class)
                            .list());
            assertThat(visible).containsExactlyInAnyOrder(me, colleague).doesNotContain(otherTenantMember, stranger);
        }

        @Test
        void usersUpdateOnlyTheirOwnProfile() {
            UUID tenant = tenant();
            UUID me = user();
            UUID colleague = user();
            membership(tenant, me, "admin", "active");
            membership(tenant, colleague, "staff", "active");

            int own = app.as(tenant, me, "admin", jdbc -> renameUser(jdbc, me));
            int other = app.as(tenant, me, "admin", jdbc -> renameUser(jdbc, colleague));

            assertThat(own).isOne();
            assertThat(other).isZero();
        }

        @Test
        void isPlatformAdminCannotBeSetByAppUser() {
            UUID tenant = tenant();
            UUID me = user();
            UUID newUser = UUID.randomUUID();

            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(
                            tenant,
                            me,
                            "admin",
                            jdbc -> jdbc.sql(
                                            "update northstar.user_profiles set is_platform_admin = true where id = :id")
                                    .param("id", me)
                                    .update()));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(
                            tenant,
                            newUser,
                            "admin",
                            jdbc -> jdbc.sql(
                                            "insert into northstar.user_profiles (id, is_platform_admin) values (:id, true)")
                                    .param("id", newUser)
                                    .update()));
        }

        @Test
        void usersCreateOnlyTheirOwnProfile() {
            UUID tenant = tenant();
            UUID me = UUID.randomUUID();

            app.run(tenant, me, "staff", jdbc -> insertProfile(jdbc, me));

            assertThat(postgres.sql("select count(*) from northstar.user_profiles where id = :id")
                            .param("id", me)
                            .query(Integer.class)
                            .single())
                    .isOne();
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(tenant, me, "staff", jdbc -> insertProfile(jdbc, UUID.randomUUID())));
        }

        private int renameUser(JdbcClient jdbc, UUID user) {
            return jdbc.sql("update northstar.user_profiles set full_name = 'Renamed' where id = :id")
                    .param("id", user)
                    .update();
        }

        private int insertProfile(JdbcClient jdbc, UUID user) {
            return jdbc.sql("insert into northstar.user_profiles (id, full_name) values (:id, 'New user')")
                    .param("id", user)
                    .update();
        }
    }

    @Nested
    class Memberships {

        @Test
        void aUserHasAtMostOneActiveMembership() {
            UUID user = user();
            membership(tenant(), user, "admin", "active");
            UUID otherTenant = tenant();

            assertFails(
                    UNIQUE_VIOLATION,
                    () -> app.run(
                            otherTenant, user(), "admin", jdbc -> insertMembership(jdbc, otherTenant, user, "active")));

            UUID invited =
                    app.as(otherTenant, user(), "admin", jdbc -> insertMembership(jdbc, otherTenant, user, "invited"));
            assertFails(
                    UNIQUE_VIOLATION,
                    () -> app.run(
                            otherTenant,
                            user(),
                            "admin",
                            jdbc -> jdbc.sql("update northstar.memberships set status = 'active' where id = :id")
                                    .param("id", invited)
                                    .update()));
        }

        @Test
        void membershipsAreNeverDeleted() {
            UUID tenant = tenant();
            UUID member = membership(tenant, user(), "staff", "active");
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(
                            tenant,
                            user(),
                            "admin",
                            jdbc -> jdbc.sql("delete from northstar.memberships where id = :id")
                                    .param("id", member)
                                    .update()));
        }

        private UUID insertMembership(JdbcClient jdbc, UUID tenant, UUID user, String status) {
            return jdbc.sql("""
                            insert into northstar.memberships (tenant_id, user_id, role, status)
                            values (:tenant, :user, 'staff', :status) returning id""")
                    .param("tenant", tenant)
                    .param("user", user)
                    .param("status", status)
                    .query(UUID.class)
                    .single();
        }
    }

    @Nested
    class StaffSkillsAndLeave {

        @Test
        void rowsCannotPointAtAnotherTenantsMember() {
            UUID otherTenantMember = membership(tenant(), user(), "staff", "active");
            UUID tenant = tenant();
            UUID admin = membership(tenant, user(), "admin", "active");

            assertFails(
                    FOREIGN_KEY_VIOLATION,
                    () -> app.run(tenant, user(), "admin", jdbc -> insertSkill(jdbc, tenant, otherTenantMember)));
            assertFails(
                    FOREIGN_KEY_VIOLATION,
                    () -> app.run(
                            tenant, user(), "admin", jdbc -> insertLeave(jdbc, tenant, otherTenantMember, admin)));
            assertFails(
                    FOREIGN_KEY_VIOLATION,
                    () -> app.run(
                            tenant, user(), "admin", jdbc -> insertLeave(jdbc, tenant, admin, otherTenantMember)));
        }

        @Test
        void skillsCanBeDeletedButLeaveCannot() {
            UUID tenant = tenant();
            UUID member = membership(tenant, user(), "staff", "active");

            int deletedSkills = app.as(tenant, user(), "admin", jdbc -> {
                insertSkill(jdbc, tenant, member);
                return jdbc.sql("delete from northstar.staff_skills where membership_id = :member")
                        .param("member", member)
                        .update();
            });
            assertThat(deletedSkills).isOne();

            app.run(tenant, user(), "admin", jdbc -> insertLeave(jdbc, tenant, member, member));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(
                            tenant,
                            user(),
                            "admin",
                            jdbc -> jdbc.sql("delete from northstar.staff_leave where membership_id = :member")
                                    .param("member", member)
                                    .update()));
        }

        @Test
        void leaveEndsOnOrAfterItStartsAndCancellationRecordsWho() {
            UUID tenant = tenant();
            UUID member = membership(tenant, user(), "staff", "active");

            assertFails(
                    CHECK_VIOLATION,
                    () -> app.run(
                            tenant,
                            user(),
                            "admin",
                            jdbc -> jdbc.sql("""
                                    insert into northstar.staff_leave (tenant_id, membership_id, starts_on, ends_on, created_by)
                                    values (:tenant, :member, date '2026-12-10', date '2026-12-09', :member)""")
                                    .param("tenant", tenant)
                                    .param("member", member)
                                    .update()));

            UUID leave = app.as(tenant, user(), "admin", jdbc -> insertLeave(jdbc, tenant, member, member));
            assertFails(
                    CHECK_VIOLATION,
                    () -> app.run(
                            tenant,
                            user(),
                            "admin",
                            jdbc -> jdbc.sql("update northstar.staff_leave set cancelled_at = now() where id = :id")
                                    .param("id", leave)
                                    .update()));
            int cancelled = app.as(
                    tenant,
                    user(),
                    "admin",
                    jdbc -> jdbc.sql(
                                    "update northstar.staff_leave set cancelled_at = now(), cancelled_by = :member where id = :id")
                            .param("member", member)
                            .param("id", leave)
                            .update());
            assertThat(cancelled).isOne();
        }

        private int insertSkill(JdbcClient jdbc, UUID tenant, UUID member) {
            return jdbc.sql(
                            "insert into northstar.staff_skills (tenant_id, membership_id, skill) values (:t, :m, 'drone')")
                    .param("t", tenant)
                    .param("m", member)
                    .update();
        }

        private UUID insertLeave(JdbcClient jdbc, UUID tenant, UUID member, UUID createdBy) {
            return jdbc.sql("""
                            insert into northstar.staff_leave (tenant_id, membership_id, starts_on, ends_on, created_by)
                            values (:tenant, :member, date '2026-12-01', date '2026-12-03', :createdBy)
                            returning id""")
                    .param("tenant", tenant)
                    .param("member", member)
                    .param("createdBy", createdBy)
                    .query(UUID.class)
                    .single();
        }
    }

    @Nested
    class Invites {

        @Test
        void onePendingInvitePerEmailIgnoringCase() {
            UUID tenant = tenant();
            UUID admin = membership(tenant, user(), "admin", "active");

            UUID first = app.as(tenant, user(), "admin", jdbc -> invite(jdbc, tenant, admin, "Asha@Example.com"));
            assertFails(
                    UNIQUE_VIOLATION,
                    () -> app.run(tenant, user(), "admin", jdbc -> invite(jdbc, tenant, admin, "asha@example.COM")));

            app.run(
                    tenant,
                    user(),
                    "admin",
                    jdbc -> jdbc.sql("update northstar.invites set revoked_at = now() where id = :id")
                            .param("id", first)
                            .update());
            UUID reinvited = app.as(tenant, user(), "admin", jdbc -> invite(jdbc, tenant, admin, "asha@example.com"));
            assertThat(reinvited).isNotNull();
        }

        @Test
        void tokenHashIsUniqueAcrossTenants() {
            UUID a = tenant();
            UUID b = tenant();
            UUID adminA = membership(a, user(), "admin", "active");
            UUID adminB = membership(b, user(), "admin", "active");
            String hash = "hash-" + UUID.randomUUID();

            app.run(a, user(), "admin", jdbc -> invite(jdbc, a, adminA, "one@example.com", hash));
            assertFails(
                    UNIQUE_VIOLATION,
                    () -> app.run(b, user(), "admin", jdbc -> invite(jdbc, b, adminB, "two@example.com", hash)));
        }

        @Test
        void inviterMustBeAMemberOfTheSameTenant() {
            UUID otherTenantAdmin = membership(tenant(), user(), "admin", "active");
            UUID tenant = tenant();
            assertFails(
                    FOREIGN_KEY_VIOLATION,
                    () -> app.run(
                            tenant, user(), "admin", jdbc -> invite(jdbc, tenant, otherTenantAdmin, "x@example.com")));
        }

        @Test
        void expiresSevenDaysAfterCreation() {
            UUID tenant = tenant();
            UUID admin = membership(tenant, user(), "admin", "active");
            UUID invite = app.as(tenant, user(), "admin", jdbc -> invite(jdbc, tenant, admin, "late@example.com"));

            assertThat(postgres.sql(
                                    "select expires_at - created_at = interval '7 days' from northstar.invites where id = :id")
                            .param("id", invite)
                            .query(Boolean.class)
                            .single())
                    .isTrue();
        }

        private UUID invite(JdbcClient jdbc, UUID tenant, UUID invitedBy, String email) {
            return invite(jdbc, tenant, invitedBy, email, "hash-" + UUID.randomUUID());
        }

        private UUID invite(JdbcClient jdbc, UUID tenant, UUID invitedBy, String email, String tokenHash) {
            return jdbc.sql("""
                            insert into northstar.invites (tenant_id, email, role, token_hash, invited_by)
                            values (:tenant, :email, 'staff', :hash, :invitedBy) returning id""")
                    .param("tenant", tenant)
                    .param("email", email)
                    .param("hash", tokenHash)
                    .param("invitedBy", invitedBy)
                    .query(UUID.class)
                    .single();
        }
    }

    @Nested
    class AuditLog {

        @Test
        void auditLogIsInsertOnly() {
            UUID tenant = tenant();
            UUID entry = app.as(
                    tenant,
                    user(),
                    "admin",
                    jdbc -> jdbc.sql("""
                            insert into northstar.audit_log (tenant_id, action, target_table)
                            values (:tenant, 'settings.updated', 'tenants') returning id""")
                            .param("tenant", tenant)
                            .query(UUID.class)
                            .single());

            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(
                            tenant,
                            user(),
                            "admin",
                            jdbc -> jdbc.sql("update northstar.audit_log set action = 'changed' where id = :id")
                                    .param("id", entry)
                                    .update()));
            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(
                            tenant,
                            user(),
                            "admin",
                            jdbc -> jdbc.sql("delete from northstar.audit_log where id = :id")
                                    .param("id", entry)
                                    .update()));
        }
    }

    /** Only admins write memberships and invites; staff keep read access (restrictive policies, data-model.md §3). */
    @Nested
    class AdminOnlyMemberWrites {

        @Test
        void staffCannotChangeTheirOwnRole() {
            UUID tenant = tenant();
            UUID staffUser = user();
            UUID own = membership(tenant, staffUser, "staff", "active");

            int updated = app.as(tenant, staffUser, "staff", jdbc -> setMembership(jdbc, own, "role", "admin"));

            assertThat(updated).isZero();
            assertThat(membershipColumn(own, "role")).isEqualTo("staff");
        }

        @Test
        void staffCannotDeactivateAMember() {
            UUID tenant = tenant();
            UUID colleague = membership(tenant, user(), "staff", "active");

            int updated =
                    app.as(tenant, user(), "staff", jdbc -> setMembership(jdbc, colleague, "status", "deactivated"));

            assertThat(updated).isZero();
            assertThat(membershipColumn(colleague, "status")).isEqualTo("active");
        }

        @Test
        void staffCannotCreateAnInvite() {
            UUID tenant = tenant();
            UUID staffMember = membership(tenant, user(), "staff", "active");

            assertFails(
                    INSUFFICIENT_PRIVILEGE,
                    () -> app.run(tenant, user(), "staff", jdbc -> insertInvite(jdbc, tenant, staffMember)));
        }

        @Test
        void staffCannotRevokeAnInvite() {
            UUID tenant = tenant();
            UUID invite = invite(tenant, membership(tenant, user(), "admin", "active"));

            int updated = app.as(tenant, user(), "staff", jdbc -> revokeInvite(jdbc, invite));

            assertThat(updated).isZero();
            assertThat(inviteRevokedAt(invite)).isNull();
        }

        @Test
        void staffCanStillReadMembersAndInvites() {
            UUID tenant = tenant();
            UUID admin = membership(tenant, user(), "admin", "active");
            membership(tenant, user(), "staff", "active");
            invite(tenant, admin);

            int members = app.as(
                    tenant,
                    user(),
                    "staff",
                    jdbc -> jdbc.sql("select count(*) from northstar.memberships")
                            .query(Integer.class)
                            .single());
            int invites = app.as(
                    tenant,
                    user(),
                    "staff",
                    jdbc -> jdbc.sql("select count(*) from northstar.invites")
                            .query(Integer.class)
                            .single());

            assertThat(members).isEqualTo(2);
            assertThat(invites).isOne();
        }

        @Test
        void adminCanChangeRolesDeactivateAndManageInvites() {
            UUID tenant = tenant();
            UUID admin = membership(tenant, user(), "admin", "active");
            UUID colleague = membership(tenant, user(), "staff", "active");

            int promoted = app.as(tenant, user(), "admin", jdbc -> setMembership(jdbc, colleague, "role", "admin"));
            int deactivated =
                    app.as(tenant, user(), "admin", jdbc -> setMembership(jdbc, colleague, "status", "deactivated"));
            UUID invite = app.as(tenant, user(), "admin", jdbc -> insertInvite(jdbc, tenant, admin));
            int revoked = app.as(tenant, user(), "admin", jdbc -> revokeInvite(jdbc, invite));

            assertThat(promoted).isOne();
            assertThat(deactivated).isOne();
            assertThat(revoked).isOne();
            assertThat(inviteRevokedAt(invite)).isNotNull();
        }

        private int setMembership(JdbcClient jdbc, UUID membership, String column, String value) {
            return jdbc.sql("update northstar.memberships set " + column + " = :value where id = :id")
                    .param("value", value)
                    .param("id", membership)
                    .update();
        }

        private UUID insertInvite(JdbcClient jdbc, UUID tenant, UUID invitedBy) {
            return jdbc.sql("""
                            insert into northstar.invites (tenant_id, email, role, token_hash, invited_by)
                            values (:tenant, :email, 'staff', :hash, :invitedBy) returning id""")
                    .param("tenant", tenant)
                    .param("email", UUID.randomUUID() + "@example.com")
                    .param("hash", "hash-" + UUID.randomUUID())
                    .param("invitedBy", invitedBy)
                    .query(UUID.class)
                    .single();
        }

        private int revokeInvite(JdbcClient jdbc, UUID invite) {
            return jdbc.sql("update northstar.invites set revoked_at = now() where id = :id")
                    .param("id", invite)
                    .update();
        }

        private UUID invite(UUID tenant, UUID invitedBy) {
            return postgres.sql("""
                            insert into northstar.invites (tenant_id, email, role, token_hash, invited_by)
                            values (:tenant, :email, 'staff', :hash, :invitedBy) returning id""")
                    .param("tenant", tenant)
                    .param("email", UUID.randomUUID() + "@example.com")
                    .param("hash", "hash-" + UUID.randomUUID())
                    .param("invitedBy", invitedBy)
                    .query(UUID.class)
                    .single();
        }

        private Object membershipColumn(UUID membership, String column) {
            return postgres.sql("select " + column + " as value from northstar.memberships where id = :id")
                    .param("id", membership)
                    .query()
                    .singleRow()
                    .get("value");
        }

        private Object inviteRevokedAt(UUID invite) {
            return postgres.sql("select revoked_at as value from northstar.invites where id = :id")
                    .param("id", invite)
                    .query()
                    .singleRow()
                    .get("value");
        }
    }

    // Fixtures, written as postgres (bypasses RLS). Random IDs and slugs keep tests independent in the shared database.

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        postgres.sql("insert into northstar.tenants (id, name, slug) values (:id, 'Test business', :slug)")
                .param("id", id)
                .param("slug", "slug-" + id)
                .update();
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        postgres.sql("insert into northstar.user_profiles (id, full_name) values (:id, 'Test user')")
                .param("id", id)
                .update();
        return id;
    }

    private UUID membership(UUID tenant, UUID user, String role, String status) {
        return postgres.sql("""
                        insert into northstar.memberships (tenant_id, user_id, role, status)
                        values (:tenant, :user, :role, :status) returning id""")
                .param("tenant", tenant)
                .param("user", user)
                .param("role", role)
                .param("status", status)
                .query(UUID.class)
                .single();
    }

    private Object tenantColumn(UUID tenant, String column) {
        return postgres.sql("select " + column + " as value from northstar.tenants where id = :id")
                .param("id", tenant)
                .query()
                .singleRow()
                .get("value");
    }

    private static void assertFails(String sqlState, ThrowingCallable call) {
        assertThatThrownBy(call).satisfies(error -> assertThat(sqlState(error)).isEqualTo(sqlState));
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }
}
