package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.AppUserDatabase;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantDirectory;
import com.northstar.tenancy.TenantScope;
import com.northstar.tenancy.WebhookRouting;
import com.northstar.tenancy.WebhookRouting.Parked;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook routing and parking before the tenant is known, and what a webhook context may do once it is (step 2.2,
 * data-model.md section 12): each call runs in a single-statement guard mode; a webhook context exists only for a
 * tenant the routing function returned, and may run only the webhook_events insert.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WebhookRoutingTests {

    private final WebhookRouting routing;
    private final DataSource dataSource;
    private final JdbcClient appJdbc;
    private final TenantDirectory directory;
    private final TransactionTemplate transactions;
    private final AppUserDatabase appUser;
    private final JdbcClient admin;
    private final TestData data;

    WebhookRoutingTests(
            @Autowired WebhookRouting routing,
            @Autowired DataSource dataSource,
            @Autowired JdbcClient appJdbc,
            @Autowired TenantDirectory directory,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired AppUserDatabase appUser,
            @Autowired PostgresAdmin postgres) {
        this.routing = routing;
        this.dataSource = dataSource;
        this.appJdbc = appJdbc;
        this.directory = directory;
        this.transactions = new TransactionTemplate(transactionManager);
        this.appUser = appUser;
        this.admin = postgres.jdbc();
        this.data = new TestData(postgres);
    }

    @Test
    void P9_routingMapsEachNumberToItsOwnTenantAndUnknownNumbersToNothing() {
        UUID tenantA = data.tenant();
        UUID tenantB = data.tenant();
        String numberA = data.whatsAppNumber(tenantA);
        String numberB = data.whatsAppNumber(tenantB);
        String unknown = "pn-" + UUID.randomUUID();

        Map<String, TenantContext> routed =
                routing.whatsAppTenants(new LinkedHashSet<>(List.of(numberA, numberB, unknown)));

        assertThat(routed).containsOnlyKeys(numberA, numberB);
        assertThat(routed.get(numberA).tenantId()).isEqualTo(tenantA);
        assertThat(routed.get(numberB).tenantId()).isEqualTo(tenantB);
        for (TenantContext context : routed.values()) {
            assertThat(context.isWebhook()).isTrue();
            assertThat(context.role()).isEqualTo(Role.WEBHOOK);
            assertThat(context.userId()).isEqualTo(TenantContext.SYSTEM_USER_ID);
            assertThat(context.hasUser()).isFalse();
        }
        assertThat(routing.whatsAppTenants(Set.of())).isEmpty();
    }

    @Test
    void theRoutingFunctionReturnsIdsOnlyAndLooksAtNoMoreThanAHundred() {
        UUID tenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        String[] many = IntStream.range(0, 100).mapToObj(i -> "pn-none-" + i).toArray(String[]::new);
        String[] withNumberLast = java.util.Arrays.copyOf(many, 101);
        withNumberLast[100] = number;

        List<Map<String, Object>> rows =
                appUser.withoutContext(jdbc -> jdbc.sql("select * from northstar.whatsapp_tenants(?)")
                        .param(withNumberLast)
                        .query()
                        .listOfRows());
        assertThat(rows).as("the 101st ID is not looked at").isEmpty();

        withNumberLast[0] = number;
        rows = appUser.withoutContext(jdbc -> jdbc.sql("select * from northstar.whatsapp_tenants(?)")
                .param(withNumberLast)
                .query()
                .listOfRows());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsOnlyKeys("phone_number_id", "tenant_id");
        assertThat(rows.getFirst()).containsEntry("tenant_id", tenant);

        Set<String> oneTooMany = new LinkedHashSet<>(List.of(many));
        oneTooMany.add("one-too-many");
        assertThatThrownBy(() -> routing.whatsAppTenants(oneTooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void P10_webhookContextCannotBeForgedOutsideRouting() {
        UUID tenant = data.tenant();
        assertThatThrownBy(() -> new TenantContext(tenant, TenantContext.SYSTEM_USER_ID, Role.WEBHOOK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only webhook routing may create a webhook context");
        assertThatThrownBy(() -> new TenantContext(tenant, UUID.randomUUID(), Role.WEBHOOK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TenantContext(tenant, TenantContext.SYSTEM_USER_ID, Role.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Role.fromDatabaseValue("webhook")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void P10_webhookContextCanOnlyInsertWebhookEvents() {
        UUID tenant = data.tenant();
        UUID otherTenant = data.tenant();
        String number = data.whatsAppNumber(tenant);
        TenantContext webhook = routing.whatsAppTenants(Set.of(number)).get(number);
        String eventId = "message:wamid.TEST" + UUID.randomUUID();

        // The one statement: allowed, and stored in the routed tenant.
        UUID stored = TenantScope.call(
                webhook,
                () -> transactions.execute(status -> appJdbc.sql(WebhookRouting.INSERT_WEBHOOK_EVENT)
                        .param(tenant)
                        .param("whatsapp")
                        .param(eventId)
                        .param("{\"object\":\"whatsapp_business_account\"}")
                        .query(UUID.class)
                        .single()));
        assertThat(admin.sql("select tenant_id from northstar.webhook_events where id = :id")
                        .param("id", stored)
                        .query(UUID.class)
                        .single())
                .isEqualTo(tenant);

        String phone = "+9100000" + (10000 + (int) (Math.random() * 89999));
        List<Probe> refused = List.of(
                new Probe(
                        "select on contacts",
                        jdbc -> jdbc.sql("select count(*) from northstar.contacts")
                                .query(Long.class)
                                .single()),
                new Probe(
                        "select on actions",
                        jdbc -> jdbc.sql("select count(*) from northstar.actions")
                                .query(Long.class)
                                .single()),
                new Probe(
                        "select on webhook_events",
                        jdbc -> jdbc.sql("select count(*) from northstar.webhook_events")
                                .query(Long.class)
                                .single()),
                // Step 2.3's tables.
                new Probe(
                        "select on conversations",
                        jdbc -> jdbc.sql("select count(*) from northstar.conversations")
                                .query(Long.class)
                                .single()),
                new Probe(
                        "select on messages",
                        jdbc -> jdbc.sql("select count(*) from northstar.messages")
                                .query(Long.class)
                                .single()),
                new Probe(
                        "select on message_templates",
                        jdbc -> jdbc.sql("select count(*) from northstar.message_templates")
                                .query(Long.class)
                                .single()),
                new Probe(
                        "select on contact_sources",
                        jdbc -> jdbc.sql("select count(*) from northstar.contact_sources")
                                .query(Long.class)
                                .single()),
                new Probe(
                        "insert into contacts",
                        jdbc -> jdbc.sql("insert into northstar.contacts (tenant_id, normalized_phone) values (?, ?)")
                                .param(tenant)
                                .param(phone)
                                .update()),
                new Probe(
                        "another webhook_events insert",
                        jdbc -> jdbc.sql(
                                        "insert into northstar.webhook_events (tenant_id, provider, provider_event_id, payload)"
                                                + " values (?, 'whatsapp', ?, '{}')")
                                .param(tenant)
                                .param("message:wamid.TEST" + UUID.randomUUID())
                                .update()),
                new Probe(
                        "update of webhook_events",
                        jdbc -> jdbc.sql("update northstar.webhook_events set processed_at = now() where id = ?")
                                .param(stored)
                                .update()),
                new Probe(
                        "set_config to another tenant",
                        jdbc -> jdbc.sql("select set_config('app.tenant_id', ?, true)")
                                .param(otherTenant.toString())
                                .query()
                                .listOfRows()));
        for (Probe probe : refused) {
            assertThatThrownBy(() -> TenantScope.run(
                            webhook,
                            () -> transactions.executeWithoutResult(
                                    status -> probe.work().accept(appJdbc))))
                    .as(probe.name())
                    .isInstanceOf(MissingTenantContextException.class)
                    .hasMessageContaining("a webhook context may only store webhook events");
        }
        // A repository call (JPA) is refused the same way, and so is reaching under the guard.
        assertThatThrownBy(() -> TenantScope.run(webhook, directory::currentTenantExists))
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> TenantScope.run(
                        webhook,
                        () -> transactions.executeWithoutResult(status -> {
                            // The transaction's own connection: not closed here, the transaction releases it.
                            Connection connection = DataSourceUtils.getConnection(dataSource);
                            try {
                                connection.unwrap(org.postgresql.PGConnection.class);
                            } catch (SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        })))
                .isInstanceOf(MissingTenantContextException.class);

        // Nothing refused reached the database: no contact was added and the stored event is unprocessed.
        assertThat(admin.sql("select count(*) from northstar.contacts where normalized_phone = :phone")
                        .param("phone", phone)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(admin.sql("select processed_at is null from northstar.webhook_events where id = :id")
                        .param("id", stored)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        // Mutation check: the database alone would allow these. The same statements, run with the same context
        // (tenant, nil user, role webhook) on a plain app_user connection without the guard, succeed. So the refusals
        // above come from the guard's webhook mode; drop that mode and this test fails. (Since step 2.3 the database
        // refuses the webhook role contacts, conversations, messages and contact sources as well: their staff policies
        // leave it out, as ConversationIsolationTests shows. So the check reads tables only the guard keeps it from.)
        UUID template = data.messageTemplate(tenant);
        appUser.run(tenant, TenantContext.SYSTEM_USER_ID, "webhook", jdbc -> {
            assertThat(jdbc.sql("select count(*) from northstar.message_templates where id = ?")
                            .param(template)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            assertThat(jdbc.sql("select count(*) from northstar.actions")
                            .query(Long.class)
                            .single())
                    .isNotNull();
            assertThat(jdbc.sql("select count(*) from northstar.webhook_events where id = ?")
                            .param(stored)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            assertThat(jdbc.sql("update northstar.webhook_events set processed_at = now() where id = ?")
                            .param(stored)
                            .update())
                    .isEqualTo(1);
        });
    }

    private record Probe(String name, Consumer<JdbcClient> work) {}

    @Test
    void P10_eachContextFreeModeAllowsOnlyItsOwnStatement() throws SQLException {
        List<Mode> modes = List.of(
                new Mode(TenantGuardedConnection::beginWhatsAppRouting, TenantGuardDataSource.WHATSAPP_ROUTING),
                new Mode(
                        TenantGuardedConnection::beginInvalidSignatureParking,
                        TenantGuardDataSource.PARK_INVALID_SIGNATURE),
                new Mode(
                        TenantGuardedConnection::beginUnknownAccountParking,
                        TenantGuardDataSource.PARK_UNKNOWN_ACCOUNT));
        List<String> others = List.of(
                "select 1",
                "select tenant_id from northstar.whatsapp_accounts",
                "select * from ingest.unrouted_webhooks",
                TenantGuardDataSource.MEMBERSHIP_LOOKUP,
                TenantGuardDataSource.DUE_ACTION_TENANTS,
                WebhookRouting.INSERT_WEBHOOK_EVENT);
        for (Mode mode : modes) {
            for (String statement : others) {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    mode.begin().accept(connection.unwrap(TenantGuardedConnection.class));
                    assertThatThrownBy(() -> connection.prepareStatement(statement))
                            .as(mode.statement() + " then " + statement)
                            .isInstanceOf(MissingTenantContextException.class);
                    assertThatThrownBy(connection::createStatement).isInstanceOf(MissingTenantContextException.class);
                    try (PreparedStatement own = connection.prepareStatement(mode.statement())) {
                        assertThat(own).isNotNull();
                    }
                    connection.rollback();
                    // The mode ends with the transaction.
                    assertThatThrownBy(() -> connection.prepareStatement(mode.statement()))
                            .isInstanceOf(MissingTenantContextException.class);
                    connection.setAutoCommit(true);
                }
            }
            try (Connection connection = dataSource.getConnection()) {
                assertThatThrownBy(() -> mode.begin().accept(connection.unwrap(TenantGuardedConnection.class)))
                        .as("outside a transaction")
                        .isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @FunctionalInterface
    private interface ModeStart {
        void accept(TenantGuardedConnection connection) throws SQLException;
    }

    private record Mode(ModeStart begin, String statement) {}

    @Test
    void P15_anInvalidSignatureIsParkedWithMetadataOnly() {
        Parked parked = routing.parkInvalidSignature("whatsapp", 1234);
        assertThat(parked.outcome()).isEqualTo(Parked.Outcome.STORED);
        Map<String, Object> row = unrouted(parked.unroutedId().orElseThrow());
        assertThat(row)
                .containsEntry("provider", "whatsapp")
                .containsEntry("signature_valid", false)
                .containsEntry("reason", "invalid_signature")
                .containsEntry("body_bytes", 1234)
                .containsEntry("payload_too_large", false);
        assertThat(row.get("provider_event_id")).isNull();
        assertThat(row.get("routing_key")).isNull();
        assertThat(row.get("payload")).isNull();
    }

    @Test
    void P16_anUnknownAccountIsParkedWithItsPayloadUpTo64KbAndOnce() {
        String eventId = "message:wamid.TEST" + UUID.randomUUID();
        Parked first = routing.parkUnknownAccount("whatsapp", eventId, "pn-unknown", 20, "{\"a\": \"b\"}");
        assertThat(first.outcome()).isEqualTo(Parked.Outcome.STORED);
        Map<String, Object> row = unrouted(first.unroutedId().orElseThrow());
        assertThat(row)
                .containsEntry("reason", "unknown_account")
                .containsEntry("signature_valid", true)
                .containsEntry("routing_key", "pn-unknown")
                .containsEntry("provider_event_id", eventId)
                .containsEntry("payload_too_large", false);
        assertThat(row.get("payload").toString()).contains("\"a\": \"b\"");

        Parked retry = routing.parkUnknownAccount("whatsapp", eventId, "pn-unknown", 20, "{\"a\": \"b\"}");
        assertThat(retry.outcome()).isEqualTo(Parked.Outcome.DUPLICATE);
        assertThat(retry.unroutedId()).isEmpty();

        String atLimit = "{\"x\": \"" + "y".repeat(65_536 - 9) + "\"}";
        assertThat(atLimit.length()).isEqualTo(65_536);
        Parked kept = routing.parkUnknownAccount(
                "whatsapp", "message:wamid.TEST" + UUID.randomUUID(), "pn-unknown", 65_536, atLimit);
        assertThat(unrouted(kept.unroutedId().orElseThrow()).get("payload")).isNotNull();

        Parked tooLarge = routing.parkUnknownAccount(
                "whatsapp", "message:wamid.TEST" + UUID.randomUUID(), "pn-unknown", 65_537, atLimit + " ");
        Map<String, Object> large = unrouted(tooLarge.unroutedId().orElseThrow());
        assertThat(large.get("payload")).isNull();
        assertThat(large).containsEntry("payload_too_large", true).containsEntry("body_bytes", 65_537);
    }

    @Test
    void theDatabaseCapsEachReasonAtItsLimitAMinuteAcrossInstances() {
        // Age every recent row out of the window, then fill the window exactly as other instances might have.
        admin.sql("update ingest.unrouted_webhooks set received_at = received_at - interval '2 minutes'"
                        + " where received_at > now() - interval '1 minute'")
                .update();
        List<UUID> filler = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 60; i++) filler.add(insertUnrouted("invalid_signature", null));
            Parked overCap = routing.parkInvalidSignature("whatsapp", 10);
            assertThat(overCap.outcome()).isEqualTo(Parked.Outcome.RATE_LIMITED);
            assertThat(overCap.unroutedId()).isEmpty();

            String duplicateId = "message:wamid.TEST" + UUID.randomUUID();
            filler.add(insertUnrouted("unknown_account", duplicateId));
            for (int i = 1; i < 600; i++) filler.add(insertUnrouted("unknown_account", "filler-" + UUID.randomUUID()));
            assertThat(routing.parkUnknownAccount("whatsapp", "message:wamid.TEST" + UUID.randomUUID(), "pn", 2, "{}")
                            .outcome())
                    .isEqualTo(Parked.Outcome.RATE_LIMITED);
            assertThat(routing.parkUnknownAccount("whatsapp", duplicateId, "pn", 2, "{}")
                            .outcome())
                    .as("a retry is still a duplicate while the cap is reached")
                    .isEqualTo(Parked.Outcome.DUPLICATE);
            assertThat(admin.sql("select count(*) from ingest.unrouted_webhooks where received_at > now() - interval"
                                    + " '1 minute'")
                            .query(Long.class)
                            .single())
                    .isEqualTo(660);
        } finally {
            admin.sql("delete from ingest.unrouted_webhooks where id = any(:ids)")
                    .param("ids", filler.toArray(UUID[]::new))
                    .update();
        }
    }

    @Test
    void nothingButTheFunctionsCanReachTheUnroutedTable() {
        assertThatThrownBy(() -> appUser.withoutContext(jdbc -> jdbc.sql(
                                "insert into ingest.unrouted_webhooks (provider, signature_valid, reason, body_bytes)"
                                        + " values ('whatsapp', false, 'invalid_signature', 1)")
                        .update()))
                .rootCause()
                .hasMessageContaining("permission denied for schema ingest");
        assertThatThrownBy(() -> appUser.withoutContext(jdbc -> jdbc.sql(
                                "select * from northstar.park_unknown_account('whatsapp', null, 'pn', 1, '{}')")
                        .query()
                        .listOfRows()))
                .rootCause()
                .hasMessageContaining("needs an event ID, a routing key and a payload");
    }

    private UUID insertUnrouted(String reason, String eventId) {
        boolean valid = reason.equals("unknown_account");
        return admin.sql("""
                        insert into ingest.unrouted_webhooks
                          (provider, provider_event_id, signature_valid, reason, routing_key, body_bytes, payload)
                        values ('whatsapp', :event, :valid, :reason, :key, 2, cast(:payload as jsonb)) returning id""")
                .param("event", eventId)
                .param("valid", valid)
                .param("reason", reason)
                .param("key", valid ? "pn-filler" : null)
                .param("payload", valid ? "{}" : null)
                .query(UUID.class)
                .single();
    }

    private Map<String, Object> unrouted(UUID id) {
        return admin.sql("""
                        select provider, provider_event_id, signature_valid, reason, routing_key, body_bytes,
                               payload::text as payload, payload_too_large
                        from ingest.unrouted_webhooks where id = :id""").param("id", id).query().singleRow();
    }
}
