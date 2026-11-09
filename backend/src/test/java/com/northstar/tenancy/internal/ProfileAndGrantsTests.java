package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.api.ConflictException;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The own-profile endpoint, the member and invite column grants, and lock trouble mapped to a retryable 409. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileAndGrantsTests {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final JdbcClient admin;
    private final AppUserDatabase app;

    ProfileAndGrantsTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired PostgresAdmin postgres,
            @Autowired AppUserDatabase app) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
        this.app = app;
    }

    @Test
    void aMemberUpdatesTheirOwnNameAndPhoneAndMeShowsTheName() {
        TestData.Member member = data.member("staff");

        assertThat(putProfile(member.userId(), "{\"fullName\": \"  Ravi Kumar \", \"phone\": \"+919812345678\"}"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        {"fullName": "Ravi Kumar", "phone": "+919812345678"}""");
        assertThat(mvc.get()
                        .uri("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(member.userId())))
                .bodyJson()
                .extractingPath("$.user.fullName")
                .isEqualTo("Ravi Kumar");

        assertThat(putProfile(member.userId(), "{\"fullName\": \" \", \"phone\": null}"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        {"fullName": null, "phone": null}""");
    }

    @Test
    void badProfileInputIs400() {
        TestData.Member member = data.member("staff");
        assertThat(putProfile(member.userId(), "{\"fullName\": \"Ravi\", \"phone\": \"98123 45678\"}"))
                .hasStatus(400)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "validation_failed", "details": {"fields": [{"field": "phone", "rule": "Pattern"}]}}""");
        assertThat(putProfile(member.userId(), "{\"fullName\": \"Ravi\", \"phone\": null, \"isPlatformAdmin\": true}"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed_request");
        assertThat(admin.sql("select is_platform_admin from northstar.user_profiles where id = :id")
                        .param("id", member.userId())
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void aSignedInUserWithoutAMembershipCannotUpdateAProfile() {
        assertThat(putProfile(UUID.randomUUID(), "{\"fullName\": \"Nobody\", \"phone\": null}"))
                .hasStatus(403)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("no_active_membership");
    }

    @Test
    void appUserCanUpdateOnlyTheGrantedMembershipColumnsAndNeverDelete() {
        TestData.Member member = data.member("admin");
        UUID staff = data.membership(member.tenantId(), data.user(), "staff", "active");

        for (String change : new String[] {
            "user_id = gen_random_uuid()", "tenant_id = gen_random_uuid()", "created_at = now()", "updated_at = now()"
        }) {
            assertRefused(member, "update northstar.memberships set " + change + " where id = '" + staff + "'");
        }
        assertRefused(member, "delete from northstar.memberships where id = '" + staff + "'");

        int updated = app.as(
                member.tenantId(),
                member.userId(),
                "admin",
                jdbc -> jdbc.sql("update northstar.memberships set whatsapp_phone = '+919800000002', role = 'staff',"
                                + " status = 'active' where id = :id")
                        .param("id", staff)
                        .update());
        assertThat(updated).isOne();
    }

    @Test
    void appUserCanUpdateOnlyRevokedAtOnInvitesAndNeverDelete() {
        TestData.Member member = data.member("admin");
        UUID invite = admin.sql("""
                        insert into northstar.invites (tenant_id, email, role, token_hash, invited_by)
                        values (:tenant, 'grant-check@example.com', 'staff', :hash, :inviter) returning id""")
                .param("tenant", member.tenantId())
                .param("hash", "hash-" + UUID.randomUUID())
                .param("inviter", member.membershipId())
                .query(UUID.class)
                .single();

        for (String change : new String[] {
            "email = 'other@example.com'",
            "role = 'admin'",
            "accepted_at = now()",
            "token_hash = 'x'",
            "expires_at = now()"
        }) {
            assertRefused(member, "update northstar.invites set " + change + " where id = '" + invite + "'");
        }
        assertRefused(member, "delete from northstar.invites where id = '" + invite + "'");

        int revoked = app.as(
                member.tenantId(),
                member.userId(),
                "admin",
                jdbc -> jdbc.sql("update northstar.invites set revoked_at = now() where id = :id")
                        .param("id", invite)
                        .update());
        assertThat(revoked).isOne();
    }

    @Test
    void deadlocksAndLockTimeoutsBecome409ConflictRetry() {
        for (String state : new String[] {"40P01", "55P03", "40001"}) {
            assertThatThrownBy(() -> Members.translated(() -> {
                        throw new CannotAcquireLockException("lock trouble", new SQLException("lock", state));
                    }))
                    .as(state)
                    .isInstanceOfSatisfying(
                            ConflictException.class,
                            conflict -> assertThat(conflict.code()).isEqualTo("conflict_retry"));
        }
        assertThatThrownBy(() -> Members.translated(() -> {
                    throw new IllegalStateException(new SQLException("trigger", "NSA01"));
                }))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("last_admin"));
    }

    private void assertRefused(TestData.Member member, String sql) {
        assertThatThrownBy(() -> app.run(
                        member.tenantId(),
                        member.userId(),
                        "admin",
                        jdbc -> jdbc.sql(sql).update()))
                .as(sql)
                .satisfies(e -> assertThat(DatabaseErrors.sqlState(e)).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    private MvcTestResult putProfile(UUID user, String json) {
        return mvc.put()
                .uri("/api/v1/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }
}
