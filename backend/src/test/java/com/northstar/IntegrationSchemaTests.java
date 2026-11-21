package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The integration tables' rules in the database (step 2.1a, data-model.md sections 9, 12 and 13): duplicate webhooks
 * insert nothing (P1 groundwork), every reference stays in its tenant, only declared columns change, tenant credentials
 * are reachable only through secrets_user, and unrouted webhooks only by app_admin, with no payload kept for a bad
 * signature and none over 64 KB for an unknown account. Since step 2.8a: one business per WABA, and the defined values of
 * a number's quality rating and messaging limit.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IntegrationSchemaTests {

    private static final String INSERT_EVENT_IF_ABSENT = """
            insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)
            values (:t, 'whatsapp', :event, '{}')
            on conflict (provider, provider_event_id) do nothing""";

    private final PostgresAdmin postgres;
    private final JdbcClient admin;
    private final AppUserDatabase app;
    private final AppUserDatabase secretsUser;
    private final PostgreSQLContainer container;
    private final TestcontainersConfiguration.TestLogins logins;
    private final TestData data;

    IntegrationSchemaTests(
            @Autowired PostgresAdmin postgres,
            @Autowired AppUserDatabase app,
            @Autowired @Qualifier("secretsUserDatabase") AppUserDatabase secretsUser,
            @Autowired PostgreSQLContainer container,
            @Autowired TestcontainersConfiguration.TestLogins logins) {
        this.postgres = postgres;
        this.admin = postgres.jdbc();
        this.app = app;
        this.secretsUser = secretsUser;
        this.container = container;
        this.logins = logins;
        this.data = new TestData(postgres);
    }

    // webhook_events

    @Test
    void P1_aDuplicateProviderEventIsNotStored() {
        TestData.Member owner = data.member("admin");
        String event = "wamid." + UUID.randomUUID();

        int first = asAdmin(
                owner,
                jdbc -> jdbc.sql(INSERT_EVENT_IF_ABSENT)
                        .param("t", owner.tenantId())
                        .param("event", event)
                        .update());
        int second = asAdmin(
                owner,
                jdbc -> jdbc.sql(INSERT_EVENT_IF_ABSENT)
                        .param("t", owner.tenantId())
                        .param("event", event)
                        .update());

        assertThat(first).isOne();
        assertThat(second).as("the second copy inserts nothing").isZero();
        assertThat(eventsWithId(event)).isOne();
    }

    @Test
    void P1_theSameProviderEventIsADuplicateWhicheverTenantItIsRoutedTo() {
        TestData.Member first = data.member("admin");
        TestData.Member second = data.member("admin");
        String event = "wamid." + UUID.randomUUID();
        asAdmin(
                first,
                jdbc -> jdbc.sql(INSERT_EVENT_IF_ABSENT)
                        .param("t", first.tenantId())
                        .param("event", event)
                        .update());

        int again = asAdmin(
                second,
                jdbc -> jdbc.sql(INSERT_EVENT_IF_ABSENT)
                        .param("t", second.tenantId())
                        .param("event", event)
                        .update());

        long seenBySecond = asAdmin(
                second,
                jdbc -> jdbc.sql("select count(*) from northstar.webhook_events")
                        .query(Long.class)
                        .single());

        assertThat(again).isZero();
        assertThat(seenBySecond)
                .as("the second tenant sees nothing of the first's event")
                .isZero();
        assertThat(eventsWithId(event)).isOne();
    }

    @Test
    void webhookEventsChangeOnlyInTheirProcessingColumnsAndAreNeverDeleted() {
        TestData.Member owner = data.member("admin");
        UUID event = webhookEvent(owner.tenantId());

        int processed = asAdmin(
                owner,
                jdbc -> jdbc.sql("update northstar.webhook_events set processed_at = now(), error = 'parse_failed'"
                                + " where id = :id")
                        .param("id", event)
                        .update());

        assertThat(processed).isOne();
        for (String statement : List.of(
                "update northstar.webhook_events set payload = '{\"x\": 1}' where id = :id",
                "update northstar.webhook_events set provider_event_id = 'changed' where id = :id",
                "update northstar.webhook_events set received_at = now() where id = :id",
                "delete from northstar.webhook_events where id = :id")) {
            assertThatThrownBy(() -> asAdmin(
                            owner,
                            jdbc -> jdbc.sql(statement).param("id", event).update()))
                    .as(statement)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
        assertThatThrownBy(() -> asAdmin(
                        owner,
                        jdbc -> jdbc.sql("update northstar.webhook_events set error = 'Token EAAB... expired'"
                                        + " where id = :id")
                                .param("id", event)
                                .update()))
                .as("error is a fixed code, never provider text")
                .rootCause()
                .hasMessageContaining("webhook_events_error_check");
    }

    @Test
    void webhookEventsAreAlwaysRoutedToATenant() {
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.webhook_events (provider, provider_event_id, payload)
                                values ('whatsapp', :event, '{}')""")
                        .param("event", "wamid." + UUID.randomUUID())
                        .update())
                .rootCause()
                .hasMessageContaining("tenant_id");
    }

    // integrations and whatsapp_accounts

    @Test
    void anIntegrationUsesOnlyItsOwnTenantsCredential() {
        TestData.Member owner = data.member("admin");
        TestData.Member outsider = data.member("admin");
        UUID own = data.credential(owner.tenantId());
        UUID foreign = data.credential(outsider.tenantId());

        // app_user has no access to the secrets schema; the foreign key check runs as the table owner.
        int inserted = asAdmin(
                owner,
                jdbc -> jdbc.sql("""
                        insert into northstar.integrations (tenant_id, provider, status, credential_id)
                        values (:t, 'whatsapp', 'connected', :c)""")
                        .param("t", owner.tenantId())
                        .param("c", own)
                        .update());

        assertThat(inserted).isOne();
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.integrations (tenant_id, provider, status, credential_id)
                                values (:t, 'razorpay', 'connected', :c)""")
                        .param("t", owner.tenantId())
                        .param("c", foreign)
                        .update())
                .as("a credential of another business")
                .rootCause()
                .hasMessageContaining("integrations_credential_fk");
    }

    @Test
    void integrationsHoldOneRowPerProviderWithFixedStatusesAndErrorCodes() {
        TestData.Member owner = data.member("admin");
        UUID integration = integration(owner.tenantId(), "whatsapp");

        assertThatThrownBy(() -> integration(owner.tenantId(), "whatsapp"))
                .rootCause()
                .hasMessageContaining("integrations_tenant_id_provider_key");
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.integrations (tenant_id, provider, status)
                                values (:t, 'razorpay', 'reconnect')""").param("t", owner.tenantId()).update())
                .rootCause()
                .hasMessageContaining("integrations_status_check");
        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.integrations (tenant_id, provider, status)
                                values (:t, 'telegram', 'connected')""").param("t", owner.tenantId()).update())
                .rootCause()
                .hasMessageContaining("integrations_provider_check");
        assertThatThrownBy(() -> asAdmin(
                        owner,
                        jdbc -> jdbc.sql("update northstar.integrations set last_error_code = 'Invalid OAuth token',"
                                        + " last_error_at = now() where id = :id")
                                .param("id", integration)
                                .update()))
                .as("last_error_code is a fixed code, never provider text")
                .rootCause()
                .hasMessageContaining("integrations_last_error_code_check");
        assertThatThrownBy(() -> asAdmin(
                        owner,
                        jdbc -> jdbc.sql("update northstar.integrations set last_error_code = 'token_expired'"
                                        + " where id = :id")
                                .param("id", integration)
                                .update()))
                .as("the code and its time are set together")
                .rootCause()
                .hasMessageContaining("integrations_error_complete");
        for (String statement : List.of(
                "update northstar.integrations set provider = 'razorpay' where id = :id",
                "delete from northstar.integrations where id = :id")) {
            assertThatThrownBy(() -> asAdmin(
                            owner,
                            jdbc -> jdbc.sql(statement).param("id", integration).update()))
                    .as(statement)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void aWhatsAppNumberBelongsToOneBusinessAndKeepsItsIds() {
        TestData.Member owner = data.member("admin");
        TestData.Member other = data.member("admin");
        UUID account = data.whatsAppAccount(owner.tenantId());
        String phoneNumberId = admin.sql("select phone_number_id from northstar.whatsapp_accounts where id = :id")
                .param("id", account)
                .query(String.class)
                .single();

        assertThatThrownBy(() -> admin.sql("""
                                insert into northstar.whatsapp_accounts (tenant_id, waba_id, phone_number_id)
                                values (:t, 'another-waba', :phone)""")
                        .param("t", other.tenantId())
                        .param("phone", phoneNumberId)
                        .update())
                .as("the same number in a second business")
                .rootCause()
                .hasMessageContaining("whatsapp_accounts_phone_number_id_key");
        int rated = asAdmin(
                owner,
                jdbc -> jdbc.sql("update northstar.whatsapp_accounts set quality_rating = 'green',"
                                + " messaging_limit = 'tier_2k' where id = :id")
                        .param("id", account)
                        .update());
        assertThat(rated).isOne();
        for (String statement : List.of(
                "update northstar.whatsapp_accounts set phone_number_id = 'moved' where id = :id",
                "update northstar.whatsapp_accounts set waba_id = 'moved' where id = :id",
                "delete from northstar.whatsapp_accounts where id = :id")) {
            assertThatThrownBy(() -> asAdmin(
                            owner,
                            jdbc -> jdbc.sql(statement).param("id", account).update()))
                    .as(statement)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    void aWabaBelongsToOneBusinessWhateverItsNumbers() {
        TestData.Member owner = data.member("admin");
        TestData.Member other = data.member("admin");
        String waba = "waba-" + UUID.randomUUID();

        // As app_user, which has no right on the extensions schema: the constraint's operator classes need none.
        for (int i = 0; i < 2; i++) {
            int inserted = asAdmin(
                    owner,
                    jdbc -> jdbc.sql("""
                                    insert into northstar.whatsapp_accounts (tenant_id, waba_id, phone_number_id)
                                    values (:t, :waba, :phone)""")
                            .param("t", owner.tenantId())
                            .param("waba", waba)
                            .param("phone", "pn-" + UUID.randomUUID())
                            .update());
            assertThat(inserted)
                    .as("number %d of one account in one business", i + 1)
                    .isOne();
        }
        assertThatThrownBy(() -> asAdmin(
                        other,
                        jdbc -> jdbc.sql("""
                                        insert into northstar.whatsapp_accounts (tenant_id, waba_id, phone_number_id)
                                        values (:t, :waba, :phone)""")
                                .param("t", other.tenantId())
                                .param("waba", waba)
                                .param("phone", "pn-" + UUID.randomUUID())
                                .update()))
                .as("the same account in a second business, even though RLS hides the first")
                .rootCause()
                .hasMessageContaining("whatsapp_accounts_one_tenant_per_waba");
        assertThat(admin.sql("""
                        select pg_get_constraintdef(oid) from pg_constraint
                        where conname = 'whatsapp_accounts_one_tenant_per_waba'""").query(String.class).single())
                .isEqualTo("EXCLUDE USING gist (waba_id WITH =, tenant_id WITH <>)");
    }

    @Test
    void qualityRatingAndMessagingLimitTakeOnlyTheirDefinedValues() {
        TestData.Member owner = data.member("admin");
        UUID account = data.whatsAppAccount(owner.tenantId());
        String quality = "update northstar.whatsapp_accounts set quality_rating = :v where id = :id";
        String limit = "update northstar.whatsapp_accounts set messaging_limit = :v where id = :id";

        for (String value : List.of("green", "yellow", "red", "unknown")) {
            int updated = asAdmin(
                    owner,
                    jdbc -> jdbc.sql(quality)
                            .param("v", value)
                            .param("id", account)
                            .update());
            assertThat(updated).as(value).isOne();
        }
        for (String value :
                List.of("tier_50", "tier_250", "tier_2k", "tier_10k", "tier_100k", "tier_unlimited", "tier_not_set")) {
            int updated = asAdmin(
                    owner,
                    jdbc -> jdbc.sql(limit)
                            .param("v", value)
                            .param("id", account)
                            .update());
            assertThat(updated).as(value).isOne();
        }
        for (String value : List.of("GREEN", "Green", "na", "flagged", "")) {
            assertThatThrownBy(() -> asAdmin(
                            owner,
                            jdbc -> jdbc.sql(quality)
                                    .param("v", value)
                                    .param("id", account)
                                    .update()))
                    .as(value)
                    .rootCause()
                    .hasMessageContaining("whatsapp_accounts_quality_rating_valid");
        }
        for (String value : List.of("TIER_2K", "tier_1k", "2000", "-1", "unlimited", "")) {
            assertThatThrownBy(() -> asAdmin(
                            owner,
                            jdbc -> jdbc.sql(limit)
                                    .param("v", value)
                                    .param("id", account)
                                    .update()))
                    .as(value)
                    .rootCause()
                    .hasMessageContaining("whatsapp_accounts_messaging_limit_valid");
        }
        int cleared = asAdmin(
                owner,
                jdbc -> jdbc.sql("update northstar.whatsapp_accounts set quality_rating = null, messaging_limit = null"
                                + " where id = :id")
                        .param("id", account)
                        .update());
        assertThat(cleared).as("null: never read from Meta").isOne();
    }

    // secrets.integration_credentials

    @Test
    void secretsUserReadsAndReplacesItsTenantsCredentialsOnly() {
        TestData.Member owner = data.member("admin");
        TestData.Member outsider = data.member("admin");
        UUID foreign = data.credential(outsider.tenantId());
        UUID credential = UUID.randomUUID();

        int inserted = asWorker(
                secretsUser,
                owner,
                jdbc -> jdbc.sql("""
                        insert into secrets.integration_credentials (id, tenant_id, provider, encrypted_secret, key_id)
                        values (:id, :t, 'whatsapp', :bytes, 'k1')""")
                        .param("id", credential)
                        .param("t", owner.tenantId())
                        .param("bytes", new byte[40])
                        .update());
        int replaced = asWorker(
                secretsUser,
                owner,
                jdbc -> jdbc.sql("update secrets.integration_credentials set encrypted_secret = :bytes,"
                                + " key_id = 'k2', rotated_at = now() where id = :id")
                        .param("bytes", new byte[41])
                        .param("id", credential)
                        .update());
        long visible = asWorker(
                secretsUser,
                owner,
                jdbc -> jdbc.sql("select count(*) from secrets.integration_credentials")
                        .query(Long.class)
                        .single());
        long foreignVisible = asWorker(
                secretsUser,
                owner,
                jdbc -> jdbc.sql("select count(*) from secrets.integration_credentials where id = :id")
                        .param("id", foreign)
                        .query(Long.class)
                        .single());

        assertThat(inserted).isOne();
        assertThat(replaced).isOne();
        assertThat(visible).isOne();
        assertThat(foreignVisible).isZero();
        long withoutContext =
                secretsUser.withoutContext(jdbc -> jdbc.sql("select count(*) from secrets.integration_credentials")
                        .query(Long.class)
                        .single());
        assertThat(withoutContext).as("no tenant context, no rows").isZero();
        for (String statement : List.of(
                "update secrets.integration_credentials set provider = 'razorpay' where id = :id",
                "update secrets.integration_credentials set tenant_id = tenant_id where id = :id",
                "delete from secrets.integration_credentials where id = :id")) {
            assertThatThrownBy(() -> asWorker(
                            secretsUser,
                            owner,
                            jdbc -> jdbc.sql(statement).param("id", credential).update()))
                    .as(statement)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
        assertThatThrownBy(() -> asWorker(
                        secretsUser,
                        owner,
                        jdbc -> jdbc.sql("update secrets.integration_credentials set key_id = 'Key One'"
                                        + " where id = :id")
                                .param("id", credential)
                                .update()))
                .rootCause()
                .hasMessageContaining("integration_credentials_key_id_check");
        assertThatThrownBy(() -> admin.sql("""
                                insert into secrets.integration_credentials (tenant_id, provider, encrypted_secret, key_id)
                                values (:t, 'whatsapp', :bytes, 'k1')""")
                        .param("t", owner.tenantId())
                        .param("bytes", "token".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .update())
                .as("shorter than a format byte, a nonce and a tag")
                .rootCause()
                .hasMessageContaining("integration_credentials_encrypted_secret_check");
    }

    @Test
    void onlySecretsUserCanReadTenantCredentials() throws SQLException {
        TestData.Member owner = data.member("admin");
        data.credential(owner.tenantId());

        // The API's login, in every role and with no context at all.
        for (String role : List.of("admin", "staff", "system")) {
            UUID user = role.equals("system") ? com.northstar.tenancy.TenantContext.SYSTEM_USER_ID : owner.userId();
            assertThatThrownBy(() -> app.as(
                            owner.tenantId(),
                            user,
                            role,
                            jdbc -> jdbc.sql("select count(*) from secrets.integration_credentials")
                                    .query(Long.class)
                                    .single()))
                    .as("app_user as " + role)
                    .rootCause()
                    .hasMessageContaining("permission denied for schema secrets");
        }
        assertThatThrownBy(() -> app.withoutContext(jdbc -> jdbc.sql(
                                "insert into secrets.integration_credentials (tenant_id, provider, encrypted_secret,"
                                        + " key_id) values (:t, 'whatsapp', :bytes, 'k1')")
                        .param("t", owner.tenantId())
                        .param("bytes", new byte[40])
                        .update()))
                .rootCause()
                .hasMessageContaining("permission denied for schema secrets");
        assertThat(selectAs("jobrunr_user", logins.jobRunrPassword(), "secrets.integration_credentials"))
                .contains("permission denied for schema secrets");
        // Roles without a login yet, and the browser roles: no privilege at all.
        assertThat(admin.sql("""
                        select r from unnest(array['app_user', 'app_admin', 'jobrunr_user', 'anon', 'authenticated',
                                                   'public']) r
                        where has_schema_privilege(r, 'secrets', 'USAGE')
                           or has_table_privilege(r, 'secrets.integration_credentials',
                                                  'SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES')""").query(String.class).list()).isEmpty();
    }

    // ingest.unrouted_webhooks

    @Test
    void onlyAppAdminCanReadUnroutedWebhooks() throws SQLException {
        assertThatThrownBy(() -> app.withoutContext(jdbc -> jdbc.sql("select count(*) from ingest.unrouted_webhooks")
                        .query(Long.class)
                        .single()))
                .rootCause()
                .hasMessageContaining("permission denied for schema ingest");
        assertThatThrownBy(() -> app.withoutContext(jdbc -> jdbc.sql("""
                                insert into ingest.unrouted_webhooks (provider, signature_valid, reason, body_bytes)
                                values ('whatsapp', false, 'invalid_signature', 10)""").update()))
                .as("app_user writes only through the allowlisted function of step 2.2")
                .rootCause()
                .hasMessageContaining("permission denied for schema ingest");
        assertThat(selectAs("jobrunr_user", logins.jobRunrPassword(), "ingest.unrouted_webhooks"))
                .contains("permission denied for schema ingest");
        assertThat(selectAs("secrets_user", logins.secretsUserPassword(), "ingest.unrouted_webhooks"))
                .contains("permission denied for schema ingest");
        assertThat(admin.sql("""
                        select p from unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) p
                        where has_table_privilege('app_admin', 'ingest.unrouted_webhooks', p)""").query(String.class).list()).containsExactly("SELECT");
    }

    @Test
    void aBadSignatureKeepsMetadataOnly() {
        assertThat(unrouted(Map.of(
                        "signature_valid", "false",
                        "reason", "'invalid_signature'",
                        "provider_event_id", "'wamid.claimed'",
                        "body_bytes", "512")))
                .as("provider, the event ID it claims, its size and the time")
                .isNull();
        assertThat(unrouted(Map.of(
                        "signature_valid", "false",
                        "reason", "'invalid_signature'",
                        "body_bytes", "512",
                        "payload", "'{\"entry\": []}'")))
                .contains("unrouted_webhooks_bad_signature_keeps_metadata_only");
        assertThat(unrouted(Map.of(
                        "signature_valid", "false",
                        "reason", "'invalid_signature'",
                        "body_bytes", "512",
                        "routing_key", "'106540352242922'")))
                .contains("unrouted_webhooks_bad_signature_keeps_metadata_only");
        assertThat(unrouted(Map.of(
                        "signature_valid", "true",
                        "reason", "'invalid_signature'",
                        "routing_key", "'106540352242922'",
                        "body_bytes", "512",
                        "payload", "'{}'")))
                .as("a verified row that claims a bad signature, valid in every other way")
                .contains("unrouted_webhooks_reason_matches_signature");
    }

    @Test
    void anUnknownAccountKeepsItsPayloadUpTo64Kilobytes() {
        assertThat(unrouted(Map.of(
                        "signature_valid", "true",
                        "reason", "'unknown_account'",
                        "routing_key", "'106540352242922'",
                        "body_bytes", "65536",
                        "payload", "'{\"entry\": []}'")))
                .isNull();
        assertThat(unrouted(Map.of(
                        "signature_valid", "true",
                        "reason", "'unknown_account'",
                        "routing_key", "'106540352242922'",
                        "body_bytes", "65537",
                        "payload", "'{\"entry\": []}'")))
                .as("over 64 KB the payload is dropped")
                .contains("unrouted_webhooks_payload_cap");
        assertThat(unrouted(Map.of(
                        "signature_valid", "true",
                        "reason", "'unknown_account'",
                        "routing_key", "'106540352242922'",
                        "body_bytes", "65537",
                        "payload_too_large", "true")))
                .isNull();
        assertThat(unrouted(Map.of(
                        "signature_valid", "true",
                        "reason", "'unknown_account'",
                        "routing_key", "'106540352242922'",
                        "body_bytes", "100")))
                .as("a small body keeps its payload")
                .contains("unrouted_webhooks_payload_cap");
        assertThat(unrouted(Map.of(
                        "signature_valid", "true",
                        "reason", "'unknown_account'",
                        "body_bytes", "100",
                        "payload", "'{}'")))
                .contains("unrouted_webhooks_unknown_account_keeps_its_key");
    }

    @Test
    void aVerifiedUnroutedEventIsStoredOnceAndUnverifiedOnesAreNotDeduplicated() {
        String event = "'wamid." + UUID.randomUUID() + "'";
        Map<String, String> verified = Map.of(
                "signature_valid", "true",
                "reason", "'unknown_account'",
                "routing_key", "'106540352242922'",
                "provider_event_id", event,
                "body_bytes", "2",
                "payload", "'{}'");
        Map<String, String> unverified = Map.of(
                "signature_valid", "false",
                "reason", "'invalid_signature'",
                "provider_event_id", event,
                "body_bytes", "2");

        postgres.rolledBack(jdbc -> {
            insertUnrouted(jdbc, verified);
            assertThatThrownBy(() -> insertUnrouted(jdbc, verified))
                    .rootCause()
                    .hasMessageContaining("unrouted_webhooks_provider_event");
        });
        postgres.rolledBack(jdbc -> {
            insertUnrouted(jdbc, unverified);
            insertUnrouted(jdbc, unverified);
            insertUnrouted(jdbc, verified);
        });
    }

    // Helpers.

    private <T> T asAdmin(TestData.Member member, Function<JdbcClient, T> work) {
        return app.as(member.tenantId(), member.userId(), "admin", work);
    }

    /** As the worker: the system actor, in the member's tenant, through the given login. */
    private static <T> T asWorker(AppUserDatabase login, TestData.Member member, Function<JdbcClient, T> work) {
        return login.as(member.tenantId(), com.northstar.tenancy.TenantContext.SYSTEM_USER_ID, "system", work);
    }

    private long eventsWithId(String event) {
        return admin.sql("select count(*) from northstar.webhook_events where provider_event_id = :event")
                .param("event", event)
                .query(Long.class)
                .single();
    }

    private UUID webhookEvent(UUID tenant) {
        return admin.sql("""
                        insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)
                        values (:t, 'whatsapp', :event, '{}') returning id""")
                .param("t", tenant)
                .param("event", "wamid." + UUID.randomUUID())
                .query(UUID.class)
                .single();
    }

    private UUID integration(UUID tenant, String provider) {
        return admin.sql("""
                        insert into northstar.integrations (tenant_id, provider, status)
                        values (:t, :provider, 'connected') returning id""")
                .param("t", tenant)
                .param("provider", provider)
                .query(UUID.class)
                .single();
    }

    /** Inserts one unrouted row (column → SQL literal) and rolls it back; the failure's message, or null if accepted. */
    private String unrouted(Map<String, String> columns) {
        try {
            postgres.rolledBack(jdbc -> {
                insertUnrouted(jdbc, columns);
            });
            return null;
        } catch (RuntimeException refused) {
            Throwable root = refused;
            while (root.getCause() != null) root = root.getCause();
            return String.valueOf(root.getMessage());
        }
    }

    private static void insertUnrouted(JdbcClient jdbc, Map<String, String> columns) {
        jdbc.sql("insert into ingest.unrouted_webhooks (provider, " + String.join(", ", columns.keySet())
                        + ") values ('whatsapp', " + String.join(", ", columns.values()) + ")")
                .update();
    }

    /** Selects from the table through a real login; the error message (the select must fail). */
    private String selectAs(String role, String password, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), role, password);
                Statement statement = connection.createStatement()) {
            statement.executeQuery("select count(*) from " + table).close();
            return "select succeeded";
        } catch (SQLException refused) {
            return refused.getMessage();
        }
    }
}
