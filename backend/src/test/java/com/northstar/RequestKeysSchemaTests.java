package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code request_keys} (step 2.10, P21; data-model.md section 3), through the real app_user login with RLS: one row per
 * member, operation and key; insert-only; staff see and write only their own keys, admins and the system actor the
 * tenant's (P11); and the checks on its values.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RequestKeysSchemaTests {

    private static final String HASH = "a".repeat(64);

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;

    RequestKeysSchemaTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void P21_aKeyIsStoredOncePerMemberAndOperation() {
        Team team = team();

        assertThat(insert(team.tenant(), team.staffUser(), "staff", team.staffMembership(), "key-1"))
                .isOne();
        assertThatThrownBy(() -> insert(team.tenant(), team.staffUser(), "staff", team.staffMembership(), "key-1"))
                .rootCause()
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
        assertThat(insert(team.tenant(), team.colleagueUser(), "staff", team.colleagueMembership(), "key-1"))
                .as("another member's key of the same text is their own")
                .isOne();
        assertThat(app.<Integer>as(
                        team.tenant(),
                        team.staffUser(),
                        "staff",
                        jdbc -> jdbc.sql("""
                                        insert into northstar.request_keys
                                          (tenant_id, membership_id, operation, idempotency_key, request_hash, target_id)
                                        values (:t, :m, 'lead.create', 'key-1', :h, gen_random_uuid())
                                        on conflict on constraint request_keys_one_per_key do nothing""")
                                .param("t", team.tenant())
                                .param("m", team.staffMembership())
                                .param("h", HASH)
                                .update()))
                .as("the claim's own statement: a repeat inserts nothing")
                .isZero();
    }

    @Test
    void P11_staffSeeAndWriteOnlyTheirOwnKeysAndAdminsTheBusinesss() {
        Team team = team();
        insert(team.tenant(), team.staffUser(), "staff", team.staffMembership(), "mine");
        insert(team.tenant(), team.colleagueUser(), "staff", team.colleagueMembership(), "theirs");
        insert(team.tenant(), team.adminUser(), "admin", team.adminMembership(), "admins");

        assertThat(keysSeenBy(team.tenant(), team.staffUser(), "staff")).containsExactly("mine");
        assertThat(keysSeenBy(team.tenant(), team.adminUser(), "admin")).containsExactly("admins", "mine", "theirs");
        assertThat(keysSeenBy(team.tenant(), TenantContext.SYSTEM_USER_ID, "system"))
                .containsExactly("admins", "mine", "theirs");
        assertThat(keysSeenBy(team.tenant(), TenantContext.SYSTEM_USER_ID, "webhook"))
                .isEmpty();
        assertThatThrownBy(() -> insert(team.tenant(), team.staffUser(), "staff", team.colleagueMembership(), "forged"))
                .as("a key in a colleague's name")
                .rootCause()
                .hasMessageContaining("row-level security");

        admin.sql("update northstar.memberships set status = 'deactivated' where id = :id")
                .param("id", team.staffMembership())
                .update();
        assertThat(keysSeenBy(team.tenant(), team.staffUser(), "staff"))
                .as("a deactivated member")
                .isEmpty();
    }

    @Test
    void P9_aKeyStaysInItsTenant() {
        Team team = team();
        Team other = team();
        insert(other.tenant(), other.staffUser(), "staff", other.staffMembership(), "elsewhere");

        assertThat(keysSeenBy(team.tenant(), team.adminUser(), "admin")).isEmpty();
        assertThatThrownBy(() -> insert(team.tenant(), team.adminUser(), "admin", other.adminMembership(), "cross"))
                .as("another tenant's membership")
                .rootCause()
                .satisfies(e -> assertThat(((SQLException) e).getMessage()).containsAnyOf("foreign key", "row-level"));
    }

    @Test
    void aKeyIsNeverChangedOrDeleted() {
        Team team = team();
        insert(team.tenant(), team.adminUser(), "admin", team.adminMembership(), "fixed");

        for (String write : List.of(
                "update northstar.request_keys set target_id = gen_random_uuid()",
                "delete from northstar.request_keys")) {
            assertThatThrownBy(() -> app.as(
                            team.tenant(),
                            team.adminUser(),
                            "admin",
                            jdbc -> jdbc.sql(write).update()))
                    .as(write)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void theValuesAreChecked() {
        Team team = team();
        record Bad(String operation, String key, String hash) {}
        for (Bad bad : List.of(
                new Bad("lead.delete", "k", HASH),
                new Bad("lead.create", "", HASH),
                new Bad("lead.create", "k".repeat(101), HASH),
                new Bad("lead.create", "has space", HASH),
                new Bad("lead.create", "k", "A".repeat(64)),
                new Bad("lead.create", "k", "a".repeat(63)))) {
            assertThatThrownBy(() -> admin.sql("""
                                    insert into northstar.request_keys
                                      (tenant_id, membership_id, operation, idempotency_key, request_hash, target_id)
                                    values (:t, :m, :o, :k, :h, gen_random_uuid())""")
                            .param("t", team.tenant())
                            .param("m", team.adminMembership())
                            .param("o", bad.operation())
                            .param("k", bad.key())
                            .param("h", bad.hash())
                            .update())
                    .as(bad.toString())
                    .rootCause()
                    .hasMessageContaining("check constraint");
        }
    }

    private record Team(
            UUID tenant,
            UUID adminUser,
            UUID adminMembership,
            UUID staffUser,
            UUID staffMembership,
            UUID colleagueUser,
            UUID colleagueMembership) {}

    private Team team() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID colleagueUser = data.user();
        UUID colleagueMembership = data.membership(owner.tenantId(), colleagueUser, "staff", "active");
        return new Team(
                owner.tenantId(),
                owner.userId(),
                owner.membershipId(),
                staffUser,
                staffMembership,
                colleagueUser,
                colleagueMembership);
    }

    private int insert(UUID tenant, UUID user, String role, UUID membership, String key) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("""
                                insert into northstar.request_keys
                                  (tenant_id, membership_id, operation, idempotency_key, request_hash, target_id)
                                values (:t, :m, 'lead.create', :k, :h, gen_random_uuid())""")
                        .param("t", tenant)
                        .param("m", membership)
                        .param("k", key)
                        .param("h", HASH)
                        .update());
    }

    private List<String> keysSeenBy(UUID tenant, UUID user, String role) {
        return app.as(
                tenant,
                user,
                role,
                jdbc -> jdbc.sql("select idempotency_key from northstar.request_keys order by idempotency_key")
                        .query(String.class)
                        .list());
    }
}
