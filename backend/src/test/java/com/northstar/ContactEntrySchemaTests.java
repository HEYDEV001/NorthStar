package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The database side of manual lead entry (step 2.10; data-model.md sections 4 and 12), through the real app_user login
 * with RLS, for every role: the split contact policies (staff may insert a contact, and still read, change and lock
 * only the contacts of their own work), and {@code northstar.contact_for_new_lead}, the allowlisted function that finds
 * and locks a contact by phone number and tells a staff member nothing about another member's open lead (P11).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ContactEntrySchemaTests {

    private static final UUID NIL_USER = TenantContext.SYSTEM_USER_ID;

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;
    private final ExecutorService threads = Executors.newCachedThreadPool();

    ContactEntrySchemaTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    // The contact insert rule

    @Test
    void P11_staffMayInsertAContactButSeeItOnlyOnceALeadOfTheirsExists() {
        Shop shop = shop();
        UUID contact = UUID.randomUUID();
        String phone = phone();

        assertThat(this.<Integer>asStaff(shop, jdbc -> insertContact(jdbc, shop.tenant(), contact, phone)))
                .isOne();
        assertThat(this.<Long>asStaff(shop, jdbc -> countContact(jdbc, contact)))
                .as("the new row is not visible to the member who inserted it")
                .isZero();
        assertThat(this.<Integer>asStaff(
                        shop,
                        jdbc -> jdbc.sql("update northstar.contacts set name = 'Asha' where id = :id")
                                .param("id", contact)
                                .update()))
                .as("nor can they change it")
                .isZero();

        data.lead(shop.tenant(), shop.version(), contact, null, shop.staffMembership());

        assertThat(this.<Long>asStaff(shop, jdbc -> countContact(jdbc, contact)))
                .isOne();
        assertThat(this.<Integer>asStaff(
                        shop,
                        jdbc -> jdbc.sql("update northstar.contacts set name = 'Asha' where id = :id")
                                .param("id", contact)
                                .update()))
                .isOne();
        assertThat(this.<String>asStaff(
                        shop,
                        jdbc -> jdbc.sql("select name from northstar.contacts where id = :id for share")
                                .param("id", contact)
                                .query(String.class)
                                .single()))
                .isEqualTo("Asha");
    }

    @Test
    void P11_aStaffInsertRevealsNothingButThatTheNumberIsTaken() {
        Shop shop = shop();
        String phone = phone();
        UUID colleagues = data.contact(shop.tenant(), phone, "Ravi Kumar");
        data.lead(shop.tenant(), shop.version(), colleagues, null, shop.colleagueMembership());

        assertThatThrownBy(() -> asStaff(shop, jdbc -> insertContact(jdbc, shop.tenant(), UUID.randomUUID(), phone)))
                .as("a plain insert of a taken number is a unique violation")
                .rootCause()
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"));
        assertThatThrownBy(() -> asStaff(
                        shop,
                        jdbc -> jdbc.sql("""
                                        insert into northstar.contacts (tenant_id, normalized_phone) values (:t, :p)
                                        returning id, name""")
                                .param("t", shop.tenant())
                                .param("p", phone())
                                .query()
                                .listOfRows()))
                .as("returning would show the new row, so it is refused")
                .rootCause()
                .hasMessageContaining("row-level security");
        for (String onConflict : List.of(
                "on conflict (tenant_id, normalized_phone) do nothing",
                "on conflict (tenant_id, normalized_phone) do update set name = 'Hijacked'")) {
            assertThatThrownBy(() -> asStaff(
                            shop,
                            jdbc -> jdbc.sql("insert into northstar.contacts (tenant_id, normalized_phone, name)"
                                            + " values (:t, :p, 'Hijacked') " + onConflict)
                                    .param("t", shop.tenant())
                                    .param("p", phone)
                                    .update()))
                    .as(onConflict + ": checked against the select policy, so refused")
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }

        assertThat(admin.sql("select name from northstar.contacts where id = :id")
                        .param("id", colleagues)
                        .query(String.class)
                        .single())
                .isEqualTo("Ravi Kumar");
        assertThat(this.<Long>asStaff(shop, jdbc -> countContact(jdbc, colleagues)))
                .isZero();
        for (String lock : List.of("for share", "for update", "for no key update", "for key share")) {
            assertThat(this.<List<UUID>>asStaff(
                            shop,
                            jdbc -> jdbc.sql("select id from northstar.contacts where id = :id " + lock)
                                    .param("id", colleagues)
                                    .query(UUID.class)
                                    .list()))
                    .as(lock)
                    .isEmpty();
        }
    }

    @Test
    void P10_onlyActiveMembersAndTheSystemInsertContacts() {
        Shop shop = shop();
        UUID deactivatedUser = data.user();
        data.membership(shop.tenant(), deactivatedUser, "staff", "deactivated");
        UUID strangerUser = data.user();

        assertThat(app.<Integer>as(shop.tenant(), shop.adminUser(), "admin", jdbc -> insertNew(jdbc, shop.tenant())))
                .isOne();
        assertThat(app.<Integer>as(shop.tenant(), NIL_USER, "system", jdbc -> insertNew(jdbc, shop.tenant())))
                .isOne();
        // Admins pass on their role alone, as in every staff policy: the app sets the role from the membership.
        record Refused(String role, UUID user, String who) {}
        for (Refused refused : List.of(
                new Refused("staff", deactivatedUser, "a deactivated staff member"),
                new Refused("staff", strangerUser, "a user with no membership here"),
                new Refused("webhook", NIL_USER, "the webhook actor"),
                new Refused("owner", shop.staffUser(), "an unknown role"),
                new Refused("", shop.staffUser(), "no role"))) {
            assertThatThrownBy(() -> app.as(
                            shop.tenant(), refused.user(), refused.role(), jdbc -> insertNew(jdbc, shop.tenant())))
                    .as(refused.who())
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
        assertThatThrownBy(() -> app.withoutContext(jdbc -> insertNew(jdbc, shop.tenant())))
                .as("no context at all (P10)")
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void W32_staffCannotInsertAContactWithConsentRecorded() {
        Shop shop = shop();

        assertThatThrownBy(() -> asStaff(
                        shop,
                        jdbc -> jdbc.sql("""
                                        insert into northstar.contacts
                                          (id, tenant_id, normalized_phone, consent_state, opt_out_at, last_opt_out_at)
                                        values (:id, :t, :p, 'opted_out', now(), now())""")
                                .param("id", UUID.randomUUID())
                                .param("t", shop.tenant())
                                .param("p", phone())
                                .update()))
                .rootCause()
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSC01"));
    }

    @Test
    void P9_noRoleInsertsAContactIntoAnotherTenant() {
        Shop shop = shop();
        Shop other = shop();

        for (Map.Entry<String, UUID> actor : Map.of(
                        "admin", shop.adminUser(),
                        "staff", shop.staffUser(),
                        "system", NIL_USER)
                .entrySet()) {
            assertThatThrownBy(() -> app.as(
                            shop.tenant(), actor.getValue(), actor.getKey(), jdbc -> insertNew(jdbc, other.tenant())))
                    .as(actor.getKey())
                    .rootCause()
                    .hasMessageContaining("row-level security");
        }
    }

    // contact_for_new_lead

    @Test
    void P11_theFunctionNamesAContactToStaffOnlyWithoutAnotherMembersOpenLead() {
        Shop shop = shop();
        String colleaguesOpen = phone();
        UUID colleagues = data.contact(shop.tenant(), colleaguesOpen, null);
        data.lead(shop.tenant(), shop.version(), colleagues, null, shop.colleagueMembership());
        String mineOpen = phone();
        UUID mine = data.contact(shop.tenant(), mineOpen, null);
        data.lead(shop.tenant(), shop.version(), mine, null, shop.staffMembership());
        String closedOnly = phone();
        UUID returning = data.contact(shop.tenant(), closedOnly, null);
        data.closeLead(data.lead(shop.tenant(), shop.version(), returning, null, shop.colleagueMembership()), "lost");
        String noLeads = phone();
        UUID bare = data.contact(shop.tenant(), noLeads, null);

        assertThat(this.<List<String>>asStaff(shop, jdbc -> lookUp(jdbc, colleaguesOpen)))
                .as("another member's open lead: no ID")
                .containsExactly("|true");
        assertThat(this.<List<String>>asStaff(shop, jdbc -> lookUp(jdbc, mineOpen)))
                .as("their own open lead")
                .containsExactly(mine + "|true");
        assertThat(this.<List<String>>asStaff(shop, jdbc -> lookUp(jdbc, closedOnly)))
                .as("a returning customer (W2)")
                .containsExactly(returning + "|false");
        assertThat(this.<List<String>>asStaff(shop, jdbc -> lookUp(jdbc, noLeads)))
                .containsExactly(bare + "|false");
        assertThat(this.<List<String>>asStaff(shop, jdbc -> lookUp(jdbc, phone())))
                .as("no contact")
                .isEmpty();

        for (String phone : List.of(colleaguesOpen, mineOpen)) {
            assertThat(app.<List<String>>as(shop.tenant(), shop.adminUser(), "admin", jdbc -> lookUp(jdbc, phone)))
                    .as("an admin sees every contact anyway")
                    .singleElement()
                    .satisfies(row -> assertThat(row).endsWith("|true").doesNotStartWith("|"));
        }
        assertThat(this.<Long>asStaff(shop, jdbc -> countContact(jdbc, returning)))
                .as("the function shows the contact to no query")
                .isZero();
    }

    @Test
    void P9_theFunctionFindsNothingForAnotherTenantOrAnyOtherRole() {
        Shop shop = shop();
        Shop other = shop();
        String phone = phone();
        data.contact(shop.tenant(), phone, null);
        data.contact(other.tenant(), phone, null);
        UUID deactivatedUser = data.user();
        data.membership(shop.tenant(), deactivatedUser, "staff", "deactivated");

        assertThat(app.<List<String>>as(other.tenant(), other.staffUser(), "staff", jdbc -> lookUp(jdbc, phone)))
                .as("the other tenant's own contact, not this one's")
                .singleElement()
                .satisfies(row -> assertThat(row).endsWith("|false"));
        assertThat(app.<List<String>>as(shop.tenant(), other.staffUser(), "staff", jdbc -> lookUp(jdbc, phone)))
                .as("a member of another tenant naming this tenant")
                .isEmpty();
        assertThat(app.<List<String>>as(shop.tenant(), shop.staffUser(), "admin", jdbc -> lookUp(jdbc, phone)))
                .as("a role the membership does not have")
                .isEmpty();
        assertThat(app.<List<String>>as(shop.tenant(), deactivatedUser, "staff", jdbc -> lookUp(jdbc, phone)))
                .as("a deactivated member")
                .isEmpty();
        for (String role : List.of("system", "webhook", "owner", "")) {
            assertThat(app.<List<String>>as(shop.tenant(), NIL_USER, role, jdbc -> lookUp(jdbc, phone)))
                    .as("role '" + role + "'")
                    .isEmpty();
        }
        assertThat(app.<List<String>>withoutContext(jdbc -> lookUp(jdbc, phone)))
                .as("no context")
                .isEmpty();
    }

    @Test
    void theFunctionLocksTheContactUntilTheCallersTransactionEnds() throws Exception {
        Shop shop = shop();
        String phone = phone();
        UUID contact = data.contact(shop.tenant(), phone, null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<List<String>> holding = threads.submit(() -> asStaff(shop, jdbc -> {
            List<String> found = lookUp(jdbc, phone);
            locked.countDown();
            await(release);
            return found;
        }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> admin.sql("select id from northstar.contacts where id = :id for update nowait")
                        .param("id", contact)
                        .query(UUID.class)
                        .list())
                .as("the inbound job's lock waits for it")
                .rootCause()
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("55P03"));
        release.countDown();
        assertThat(holding.get(30, TimeUnit.SECONDS)).containsExactly(contact + "|false");
        assertThat(admin.sql("select id from northstar.contacts where id = :id for update nowait")
                        .param("id", contact)
                        .query(UUID.class)
                        .list())
                .as("released at the end of the transaction")
                .containsExactly(contact);
    }

    // Helpers

    private record Shop(
            UUID tenant,
            UUID version,
            UUID adminUser,
            UUID staffUser,
            UUID staffMembership,
            UUID colleagueMembership) {}

    private Shop shop() {
        TestData.Member owner = data.member("admin");
        UUID staffUser = data.user();
        UUID staffMembership = data.membership(owner.tenantId(), staffUser, "staff", "active");
        UUID colleague = data.membership(owner.tenantId(), data.user(), "staff", "active");
        return new Shop(
                owner.tenantId(),
                data.workflowVersion(owner.tenantId()),
                owner.userId(),
                staffUser,
                staffMembership,
                colleague);
    }

    private <T> T asStaff(Shop shop, Function<JdbcClient, T> work) {
        return app.as(shop.tenant(), shop.staffUser(), "staff", work);
    }

    private static int insertContact(JdbcClient jdbc, UUID tenant, UUID id, String phone) {
        return jdbc.sql("insert into northstar.contacts (id, tenant_id, normalized_phone) values (:id, :t, :p)")
                .param("id", id)
                .param("t", tenant)
                .param("p", phone)
                .update();
    }

    private static int insertNew(JdbcClient jdbc, UUID tenant) {
        return insertContact(jdbc, tenant, UUID.randomUUID(), phone());
    }

    private static long countContact(JdbcClient jdbc, UUID contact) {
        return jdbc.sql("select count(*) from northstar.contacts where id = :id")
                .param("id", contact)
                .query(Long.class)
                .single();
    }

    /** contact_id|has_open_lead per row the function returns (an empty ID when it names none). */
    private static List<String> lookUp(JdbcClient jdbc, String phone) {
        return jdbc.sql("""
                        select coalesce(contact_id::text, '') || '|' || has_open_lead
                        from northstar.contact_for_new_lead(:p)""").param("p", phone).query(String.class).list();
    }

    private static String phone() {
        return "+9197" + (10_000_000 + (long) (Math.random() * 89_999_999L));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
