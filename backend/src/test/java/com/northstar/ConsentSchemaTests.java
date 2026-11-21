package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The database side of opt-out (step 2.5, W32; data-model.md sections 3, 4 and 6), through the real app_user login with
 * RLS: the action recipient and cancel reason, the backstop that refuses a runnable action for an opted-out contact
 * (NSA01), the guard that keeps an opt-out final and lets only the system change consent (NSC01), and the tenant's own
 * keywords.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConsentSchemaTests {

    private static final UUID NIL_USER = TenantContext.SYSTEM_USER_ID;

    private final AppUserDatabase app;
    private final TestData data;
    private final JdbcClient admin;

    ConsentSchemaTests(@Autowired AppUserDatabase app, @Autowired PostgresAdmin postgres) {
        this.app = app;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    // The backstop on actions (NSA01)

    @Test
    void W32_theDatabaseRefusesARunnableActionForAnOptedOutContact() {
        UUID tenant = data.tenant();
        UUID optedOut = data.contact(tenant);
        optOut(optedOut);

        for (String status : List.of("pending", "needs_review", "processing", "paused")) {
            assertThatThrownBy(() -> asSystem(tenant, jdbc -> insertAction(jdbc, tenant, optedOut, status, null)))
                    .as("inserting a " + status + " action")
                    .rootCause()
                    .hasMessageContaining("opted out");
        }
        assertThatThrownBy(() -> insertAction(admin, tenant, optedOut, "pending", null))
                .as("for every role, the table's owner and superusers included")
                .rootCause()
                .hasMessageContaining("opted out");
        asSystem(
                tenant,
                jdbc -> assertThat(insertAction(jdbc, tenant, optedOut, "cancelled", "contact_opted_out"))
                        .as("recording a refused action is allowed")
                        .isNotNull());
        asSystem(
                tenant,
                jdbc -> assertThat(insertAction(jdbc, tenant, null, "pending", null))
                        .as("an action with no recipient is never checked")
                        .isNotNull());
    }

    @Test
    void W32_claimingAnActionIsRefusedOnceItsRecipientOptedOutButCancellingItIsNot() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        UUID claimable = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        UUID cancellable = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        UUID inFlight = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        asSystem(tenant, jdbc -> claim(jdbc, inFlight));
        optOut(contact);

        assertThatThrownBy(() -> asSystem(tenant, jdbc -> claim(jdbc, claimable)))
                .rootCause()
                .hasMessageContaining("opted out")
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSA01"));
        assertThatThrownBy(() -> asSystem(
                        tenant, jdbc -> jdbc.sql("""
                                        update northstar.actions set attempts = attempts + 1,
                                          lease_expires_at = now() + interval '5 minutes'
                                        where id = :id""").param("id", inFlight).update()))
                .as("taking over a lost attempt is a new claim")
                .rootCause()
                .hasMessageContaining("opted out");
        asSystem(
                tenant,
                jdbc -> assertThat(jdbc.sql("""
                                update northstar.actions
                                set status = 'cancelled', cancel_reason = 'contact_opted_out', next_attempt_at = null
                                where id = :id""").param("id", cancellable).update())
                        .isOne());
        asSystem(
                tenant,
                jdbc -> assertThat(jdbc.sql("""
                                update northstar.actions set status = 'pending', lease_expires_at = null,
                                  next_attempt_at = now() + interval '30 seconds',
                                  last_error_class = 'retryable', last_error_code = 'http_503'
                                where id = :id""").param("id", inFlight).update())
                        .as("an attempt in flight can still record its outcome; its next claim is refused")
                        .isOne());
    }

    @Test
    void W32_aStaffMembersActionForAContactTheyCannotSeeIsRefused() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID staffUser = data.user();
        data.membership(tenant, staffUser, "staff", "active");
        UUID unassigned = data.contact(tenant);

        assertThatThrownBy(() -> app.as(
                        tenant, staffUser, "staff", jdbc -> insertAction(jdbc, tenant, unassigned, "pending", null)))
                .as("fails closed")
                .rootCause()
                .hasMessageContaining("not visible");
    }

    // The recipient and the cancel reason

    @Test
    void anActionsRecipientIsItsTenantsOwnContactAndWrittenOnce() {
        UUID tenant = data.tenant();
        UUID theirContact = data.contact(data.tenant());
        // As cancelled, which the backstop lets through, so the foreign key itself is what refuses it.
        assertThatThrownBy(() -> insertAction(admin, tenant, theirContact, "cancelled", "contact_opted_out"))
                .rootCause()
                .hasMessageContaining("actions_recipient_fk");
        assertThatThrownBy(() -> insertAction(admin, tenant, theirContact, "pending", null))
                .as("and a runnable one is refused before it gets there")
                .rootCause()
                .hasMessageContaining("not visible");

        assertThat(appUserColumns("INSERT")).contains("recipient_contact_id", "cancel_reason");
        assertThat(appUserColumns("UPDATE")).contains("cancel_reason").doesNotContain("recipient_contact_id");
        UUID contact = data.contact(tenant);
        UUID action = asSystem(tenant, jdbc -> insertAction(jdbc, tenant, contact, "pending", null));
        assertThatThrownBy(() -> asSystem(
                        tenant,
                        jdbc -> jdbc.sql("update northstar.actions set recipient_contact_id = null where id = :id")
                                .param("id", action)
                                .update()))
                .rootCause()
                .hasMessageContaining("permission denied");
        assertThat(admin.sql("""
                        select indexdef from pg_indexes
                        where schemaname = 'northstar' and indexname = 'actions_open_by_recipient'""").query(String.class).single())
                .contains("(tenant_id, recipient_contact_id)")
                .contains("needs_review");
    }

    @Test
    void aCancelReasonIsAFixedCodeSetExactlyWhenTheActionIsCancelled() {
        UUID tenant = data.tenant();
        for (String[] bad : new String[][] {
            {"cancelled", null}, {"pending", "contact_opted_out"}, {"cancelled", "Contact opted out"}, {"cancelled", ""}
        }) {
            assertThatThrownBy(() -> insertAction(admin, tenant, null, bad[0], bad[1]))
                    .as(bad[0] + " with " + bad[1])
                    .rootCause()
                    .hasMessageContaining("check constraint");
        }
    }

    // The consent guard on contacts (NSC01)

    @Test
    void W32_anOptOutIsFinalAndItsTimeIsSetOnceForEveryRole() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        asSystem(
                tenant,
                jdbc -> assertThat(jdbc.sql("""
                                update northstar.contacts set consent_state = 'opted_out', opt_out_at = now()
                                where id = :id""").param("id", contact).update()).isOne());

        for (String change : List.of(
                "consent_state = 'unknown'",
                "consent_state = 'opted_in', opted_in_at = now()",
                "opt_out_at = now() + interval '1 day'",
                "opt_out_at = null")) {
            assertThatThrownBy(() -> asSystem(tenant, jdbc -> updateContact(jdbc, contact, change)))
                    .as("the system: " + change)
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSC01"));
            assertThatThrownBy(() -> updateContact(admin, contact, change))
                    .as("an operator: " + change)
                    .rootCause()
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("NSC01"));
        }
        asSystem(
                tenant,
                jdbc -> assertThat(updateContact(jdbc, contact, "name = 'Renamed'"))
                        .as("the rest of the contact can still change")
                        .isOne());
        assertThat(admin.sql("select consent_state from northstar.contacts where id = :id")
                        .param("id", contact)
                        .query(String.class)
                        .single())
                .isEqualTo("opted_out");
    }

    @Test
    void W32_onlyTheSystemChangesAContactsConsent() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        UUID contact = data.contact(tenant);

        for (String change : List.of(
                "consent_state = 'opted_out', opt_out_at = now()",
                "consent_state = 'opted_in', opted_in_at = now()",
                "opted_in_at = now()")) {
            assertThatThrownBy(() ->
                            app.run(tenant, owner.userId(), "admin", jdbc -> updateContact(jdbc, contact, change)))
                    .as("an admin: " + change)
                    .rootCause()
                    .hasMessageContaining("Only the system");
        }
        assertThatThrownBy(() -> app.run(
                        tenant,
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("""
                                        insert into northstar.contacts (tenant_id, normalized_phone, consent_state, opted_in_at)
                                        values (:t, '+919999900001', 'opted_in', now())""").param("t", tenant).update()))
                .as("nor can a new contact arrive with consent recorded")
                .rootCause()
                .hasMessageContaining("Only the system");
        app.run(
                tenant,
                owner.userId(),
                "admin",
                jdbc -> assertThat(updateContact(jdbc, contact, "name = 'Edited'"))
                        .as("an admin still edits the contact's other columns")
                        .isOne());
        asSystem(
                tenant,
                jdbc -> assertThat(updateContact(jdbc, contact, "consent_state = 'opted_in', opted_in_at = now()"))
                        .isOne());
    }

    @Test
    void anOptedOutContactHasItsOptOutTime() {
        UUID tenant = data.tenant();
        UUID contact = data.contact(tenant);
        assertThatThrownBy(() -> updateContact(admin, contact, "consent_state = 'opted_out'"))
                .rootCause()
                .hasMessageContaining("contacts_opted_out_has_time");
    }

    // The tenant's keywords

    @Test
    void W32_aTenantsKeywordsAreCheckedAndNotWritableByTheAppYet() {
        TestData.Member owner = data.member("admin");
        UUID tenant = owner.tenantId();
        assertThat(admin.sql("select opt_out_keywords::text from northstar.tenants where id = :t")
                        .param("t", tenant)
                        .query(String.class)
                        .single())
                .as("none by default")
                .isEqualTo("{}");

        assertThatThrownBy(() -> admin.sql("""
                                update northstar.tenants
                                set opt_out_keywords = array(select 'word ' || g from generate_series(1, 21) g)
                                where id = :t""").param("t", tenant).update())
                .rootCause()
                .hasMessageContaining("tenants_opt_out_keywords_valid");
        assertThatThrownBy(() -> admin.sql(
                                "update northstar.tenants set opt_out_keywords = array['stop', null] where id = :t")
                        .param("t", tenant)
                        .update())
                .rootCause()
                .hasMessageContaining("tenants_opt_out_keywords_valid");

        assertThatThrownBy(() -> app.run(
                        tenant,
                        owner.userId(),
                        "admin",
                        jdbc -> jdbc.sql("update northstar.tenants set opt_out_keywords = array['halt'] where id = :t")
                                .param("t", tenant)
                                .update()))
                .as("the settings step adds the write path")
                .rootCause()
                .hasMessageContaining("permission denied");
        admin.sql("update northstar.tenants set opt_out_keywords = array['halt'] where id = :t")
                .param("t", tenant)
                .update();
        String seen = asSystem(
                tenant,
                jdbc -> jdbc.sql("select opt_out_keywords::text from northstar.tenants")
                        .query(String.class)
                        .single());
        assertThat(seen).as("the system reads its own tenant's keywords").isEqualTo("{halt}");
    }

    // Helpers

    private <T> T asSystem(UUID tenant, Function<JdbcClient, T> work) {
        return app.as(tenant, NIL_USER, "system", work);
    }

    private void optOut(UUID contact) {
        admin.sql("update northstar.contacts set consent_state = 'opted_out', opt_out_at = now() where id = :id")
                .param("id", contact)
                .update();
    }

    private static UUID insertAction(JdbcClient jdbc, UUID tenant, UUID recipient, String status, String reason) {
        return jdbc.sql("""
                        insert into northstar.actions
                          (tenant_id, type, status, idempotency_key, mode, next_attempt_at, recipient_contact_id,
                           cancel_reason)
                        values (:t, 'test.fake.contact', :status, :key, 'automatic',
                                case when :status = 'pending' then now() end, :recipient, :reason)
                        returning id""")
                .param("t", tenant)
                .param("status", status)
                .param("key", "schema:" + UUID.randomUUID())
                .param("recipient", recipient)
                .param("reason", reason)
                .query(UUID.class)
                .single();
    }

    /** A claim as the executor makes it: processing under a lease. */
    private static int claim(JdbcClient jdbc, UUID action) {
        return jdbc.sql("""
                        update northstar.actions set status = 'processing', attempts = attempts + 1,
                          next_attempt_at = null, last_attempt_at = now(), lease_expires_at = now() + interval '5 minutes'
                        where id = :id""").param("id", action).update();
    }

    private static int updateContact(JdbcClient jdbc, UUID contact, String assignments) {
        return jdbc.sql("update northstar.contacts set " + assignments + " where id = :id")
                .param("id", contact)
                .update();
    }

    private List<String> appUserColumns(String privilege) {
        return admin.sql("""
                        select a.attname from pg_attribute a
                        where a.attrelid = 'northstar.actions'::regclass and a.attnum > 0 and not a.attisdropped
                          and has_column_privilege('app_user', a.attrelid, a.attnum, :p)""").param("p", privilege).query(String.class).list();
    }
}
