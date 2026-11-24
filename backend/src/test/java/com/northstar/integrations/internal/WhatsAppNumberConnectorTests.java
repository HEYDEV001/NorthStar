package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.devtools.LocalToolException;
import com.northstar.integrations.internal.WhatsAppNumberConnector.Connected;
import com.northstar.integrations.internal.WhatsAppNumberConnector.Request;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.TenantDirectory;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Connecting a WhatsApp number for the Phase 2 gate (step 2.11), against Postgres with RLS on: what is stored and
 * audited, the token encrypted at rest and never shown, another business's number refused (P9), and running it again.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, WhatsAppSendTests.WorkerCredentials.class})
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppNumberConnectorTests {

    private final WhatsAppNumberConnector connector;
    private final CredentialStore credentials;
    private final TestData data;
    private final JdbcClient admin;

    WhatsAppNumberConnectorTests(
            @Autowired JdbcClient jdbc,
            @Autowired PlatformTransactionManager transactionManager,
            @Autowired TenantDirectory tenants,
            @Autowired AuditTrail audit,
            @Autowired CredentialStore credentials,
            @Autowired PostgresAdmin postgres) {
        this.connector = new WhatsAppNumberConnector(jdbc, transactionManager, tenants, audit, credentials);
        this.credentials = credentials;
        this.data = new TestData(postgres);
        this.admin = postgres.jdbc();
    }

    @Test
    void connectingStoresTheNumberTheEncryptedTokenTheIntegrationAndTheAuditEntries() {
        UUID tenant = data.tenant();
        UUID untouched = data.tenant();
        String token = token();
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        Request request = request(tenant);

        Connected connected = connector.connect(request, bytes);

        assertThat(connected.numberAdded()).isTrue();
        assertThat(connected.tokenReplaced()).isFalse();
        assertThat(account(request.phoneNumberId()))
                .contains(tenant + "|" + request.wabaId() + "|" + request.displayNumber());
        UUID credential = WhatsAppNumberConnector.credentialId(tenant);
        assertThat(integration(tenant)).isEqualTo("connected|" + credential + "|none");
        assertThat(readToken(tenant)).contains(token);
        assertThat(admin.sql("""
                        select provider || '|' || key_id || '|' || (rotated_at is null) || '|'
                               || (position(convert_to(:token, 'UTF8') in encrypted_secret) = 0)
                        from secrets.integration_credentials where tenant_id = :t""")
                        .param("token", token)
                        .param("t", tenant)
                        .query(String.class)
                        .list())
                .as("one credential, encrypted under the active key, the token nowhere in it")
                .containsExactly("whatsapp|test-1|true|true");
        assertThat(audit(tenant))
                .containsExactlyInAnyOrder(
                        "whatsapp_account.added|whatsapp_accounts|" + connected.accountId() + "|no user|null",
                        "integration.connected|integrations|" + connected.integrationId() + "|no user|null");
        assertThat(bytes).as("the caller's copy is wiped").containsOnly(0);
        assertThat(rowsOf(untouched))
                .containsOnly(Map.entry("accounts", 0L), Map.entry("integrations", 0L), Map.entry("credentials", 0L));
    }

    @Test
    void P9_anotherBusinessesNumberOrAccountIsRefusedAlikeAndNothingIsStored() {
        UUID theirs = data.tenant();
        Request taken = request(theirs);
        connector.connect(taken, bytes());
        UUID tenant = data.tenant();

        Request sameNumber =
                new Request(tenant, taken.phoneNumberId(), "waba-" + UUID.randomUUID(), taken.displayNumber());
        Request sameAccount = new Request(tenant, id(), taken.wabaId(), taken.displayNumber());

        for (Request refused : List.of(sameNumber, sameAccount)) {
            assertThatThrownBy(() -> connector.connect(refused, bytes()))
                    .as(refused == sameNumber ? "their number" : "their WhatsApp Business Account")
                    .isInstanceOfSatisfying(LocalToolException.class, e -> {
                        assertThat(e.code()).isEqualTo("number_taken");
                        assertThat(e.getMessage())
                                .as("it names no business")
                                .doesNotContain(
                                        theirs.toString(), taken.wabaId(), taken.phoneNumberId(), "Test business");
                    });
        }
        assertThat(rowsOf(tenant))
                .containsOnly(Map.entry("accounts", 0L), Map.entry("integrations", 0L), Map.entry("credentials", 0L));
        assertThat(audit(tenant)).isEmpty();
        assertThat(account(taken.phoneNumberId()))
                .as("the other business's number is as it was")
                .contains(theirs + "|" + taken.wabaId() + "|" + taken.displayNumber());
        assertThat(readToken(theirs)).isPresent();
    }

    @Test
    void P9_anUnknownBusinessIsRefusedAndNothingIsStored() {
        UUID nobody = UUID.randomUUID();

        assertThatThrownBy(() -> connector.connect(request(nobody), bytes()))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("unknown_tenant"));
        assertThat(rowsOf(nobody))
                .containsOnly(Map.entry("accounts", 0L), Map.entry("integrations", 0L), Map.entry("credentials", 0L));
    }

    @Test
    void runningItAgainReplacesTheTokenAndChangesNothingElse() {
        UUID tenant = data.tenant();
        Request request = request(tenant);
        String first = token();
        String second = token();
        Connected before = connector.connect(request, first.getBytes(StandardCharsets.UTF_8));

        Connected after = connector.connect(request, second.getBytes(StandardCharsets.UTF_8));

        assertThat(after.accountId()).isEqualTo(before.accountId());
        assertThat(after.integrationId()).isEqualTo(before.integrationId());
        assertThat(after.numberAdded()).isFalse();
        assertThat(after.tokenReplaced()).isTrue();
        assertThat(rowsOf(tenant))
                .containsOnly(Map.entry("accounts", 1L), Map.entry("integrations", 1L), Map.entry("credentials", 1L));
        assertThat(readToken(tenant)).contains(second);
        assertThat(admin.sql("select rotated_at is not null from secrets.integration_credentials where tenant_id = :t")
                        .param("t", tenant)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(audit(tenant))
                .hasSize(3)
                .contains("integration.token_replaced|integrations|" + after.integrationId() + "|no user|null");
    }

    @Test
    void aChangedDisplayNumberIsUpdatedAndAnIntegrationInErrorReconnects() {
        UUID tenant = data.tenant();
        Request request = request(tenant);
        Connected connected = connector.connect(request, bytes());
        admin.sql("""
                        update northstar.integrations
                        set status = 'error', last_error_code = 'access_token_invalid', last_error_at = now()
                        where id = :id""").param("id", connected.integrationId()).update();

        Request renumbered = new Request(tenant, request.phoneNumberId(), request.wabaId(), "+1 555 000 9999");
        connector.connect(renumbered, bytes());

        assertThat(account(request.phoneNumberId())).contains(tenant + "|" + request.wabaId() + "|+1 555 000 9999");
        assertThat(integration(tenant))
                .isEqualTo("connected|" + WhatsAppNumberConnector.credentialId(tenant) + "|none");
    }

    @Test
    void aSecondNumberOfTheBusinessSharesItsCredentialAndAnotherWabaIsRefused() {
        UUID tenant = data.tenant();
        Request first = request(tenant);
        connector.connect(first, bytes());

        Request second = new Request(tenant, id(), first.wabaId(), "+15550007777");
        Connected connected = connector.connect(second, bytes());

        assertThat(connected.numberAdded()).isTrue();
        assertThat(rowsOf(tenant))
                .containsOnly(Map.entry("accounts", 2L), Map.entry("integrations", 1L), Map.entry("credentials", 1L));
        assertThatThrownBy(() -> connector.connect(
                        new Request(tenant, first.phoneNumberId(), "waba-" + UUID.randomUUID(), "+15550001234"),
                        bytes()))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("waba_mismatch"));
        assertThat(account(first.phoneNumberId())).get().asString().startsWith(tenant + "|" + first.wabaId() + "|");
    }

    @Test
    void anInterruptedRunIsFinishedByTheNextOne() {
        UUID tenant = data.tenant();
        Request request = request(tenant);
        // The run stopped after the number and the credential: no integration, so nothing can send or sync yet.
        UUID account = admin.sql("""
                        insert into northstar.whatsapp_accounts (tenant_id, waba_id, phone_number_id, display_phone)
                        values (:t, :w, :p, '+15550001234') returning id""")
                .param("t", tenant)
                .param("w", request.wabaId())
                .param("p", request.phoneNumberId())
                .query(UUID.class)
                .single();
        SystemScope.call(tenant, () -> {
            try (Secret old = Secret.ofUtf8(token())) {
                return credentials.insertIfAbsent(WhatsAppNumberConnector.credentialId(tenant), "whatsapp", old, null);
            }
        });
        String token = token();

        Connected connected = connector.connect(request, token.getBytes(StandardCharsets.UTF_8));

        assertThat(connected.accountId()).isEqualTo(account);
        assertThat(connected.numberAdded()).isFalse();
        assertThat(connected.tokenReplaced()).isTrue();
        assertThat(integration(tenant))
                .isEqualTo("connected|" + WhatsAppNumberConnector.credentialId(tenant) + "|none");
        assertThat(readToken(tenant)).contains(token);
        assertThat(audit(tenant))
                .containsExactly("integration.connected|integrations|" + connected.integrationId() + "|no user|null");
    }

    @Test
    void theTokenNeverReachesOutputErrorsOrStringForms(CapturedOutput output) {
        UUID tenant = data.tenant();
        Request request = request(tenant);
        String token = token();
        Map<String, Level> levels = new LinkedHashMap<>();
        for (String name : List.of(
                "org.springframework.jdbc", "org.springframework.transaction", "org.hibernate", "com.zaxxer.hikari")) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            levels.put(name, logger.getLevel());
            logger.setLevel(Level.TRACE);
        }
        try {
            connector.connect(request, token.getBytes(StandardCharsets.UTF_8));
            connector.connect(request, token.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> connector.connect(
                            new Request(data.tenant(), request.phoneNumberId(), "waba-x", "+15550001234"),
                            token.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(LocalToolException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(token));
        } finally {
            levels.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
        }

        assertThat(output).as("every query the runs made is logged").contains("northstar.whatsapp_accounts");
        assertThat(output).doesNotContain(token);
        assertThat(request.toString()).doesNotContain(request.phoneNumberId(), request.wabaId(), "+1");
        assertThat(connector.toString()).isEqualTo("WhatsAppNumberConnector");
    }

    @Test
    void aWrongArgumentIsRefusedByName() {
        UUID tenant = UUID.randomUUID();
        for (Runnable bad : List.<Runnable>of(
                () -> new Request(tenant, "", "waba", "+15550001234"),
                () -> new Request(tenant, "has space", "waba", "+15550001234"),
                () -> new Request(tenant, "1".repeat(65), "waba", "+15550001234"),
                () -> new Request(tenant, "1", "wa/ba", "+15550001234"),
                () -> new Request(tenant, "1", "waba", "ask me"))) {
            assertThatThrownBy(bad::run)
                    .isInstanceOfSatisfying(
                            LocalToolException.class, e -> assertThat(e.code()).isEqualTo("argument_invalid"));
        }
    }

    // Helpers

    private static Request request(UUID tenant) {
        return new Request(tenant, id(), "waba-" + UUID.randomUUID(), "+1 555-000-1234");
    }

    private static String id() {
        return "pn-" + UUID.randomUUID();
    }

    /** A token shaped like Meta's, which is never a real one. */
    private static String token() {
        return "EAAGtest" + UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] bytes() {
        return token().getBytes(StandardCharsets.UTF_8);
    }

    private Optional<String> readToken(UUID tenant) {
        return SystemScope.call(
                tenant,
                () -> credentials
                        .read(WhatsAppNumberConnector.credentialId(tenant))
                        .map(secret -> {
                            try (Secret open = secret) {
                                return open.utf8();
                            }
                        }));
    }

    /** tenant|waba|display number */
    private Optional<String> account(String phoneNumberId) {
        return admin.sql("""
                        select tenant_id || '|' || waba_id || '|' || display_phone
                        from northstar.whatsapp_accounts where phone_number_id = :p""").param("p", phoneNumberId).query(String.class).optional();
    }

    /** status|credential|last error */
    private String integration(UUID tenant) {
        return admin.sql("""
                        select status || '|' || credential_id || '|' || coalesce(last_error_code, 'none')
                        from northstar.integrations where tenant_id = :t and provider = 'whatsapp'""").param("t", tenant).query(String.class).single();
    }

    private Map<String, Long> rowsOf(UUID tenant) {
        Map<String, Long> rows = new LinkedHashMap<>();
        Map.of(
                        "accounts", "northstar.whatsapp_accounts",
                        "integrations", "northstar.integrations",
                        "credentials", "secrets.integration_credentials")
                .forEach((name, table) -> rows.put(
                        name,
                        admin.sql("select count(*) from " + table + " where tenant_id = :t")
                                .param("t", tenant)
                                .query(Long.class)
                                .single()));
        return rows;
    }

    /** action|target_table|target_id|actor|metadata, per entry of the tenant. */
    private List<String> audit(UUID tenant) {
        return admin.sql("""
                        select action || '|' || target_table || '|' || target_id || '|'
                               || coalesce(actor_user_id::text, 'no user') || '|' || coalesce(metadata::text, 'null')
                        from northstar.audit_log where tenant_id = :t""").param("t", tenant).query(String.class).list();
    }
}
