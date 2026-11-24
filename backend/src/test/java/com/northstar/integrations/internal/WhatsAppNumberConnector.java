package com.northstar.integrations.internal;

import com.northstar.devtools.LocalToolException;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.AuditTrail;
import com.northstar.tenancy.TenantDirectory;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Connects a WhatsApp number to an existing business, for the Phase 2 gate (step 2.11; data-model.md section 9,
 * integration-specs.md section 7). It is test-source code run by {@code scripts/local/gate connect} against the local
 * database only: businesses connect their own numbers through Embedded Signup from Phase 8, and nothing here ships.
 *
 * <p>As the system actor in the tenant, in this order, each its own transaction, so a number that cannot be connected
 * leaves no token behind, and running it again finishes what an interrupted run began:
 *
 * <ol>
 *   <li>the number ({@code whatsapp_accounts}, as app_user): added (audit entry {@code whatsapp_account.added}), or its
 *       display number updated. A number, or a WhatsApp Business Account, that another business holds is refused
 *       without naming that business (P9);
 *   <li>the token, encrypted by the worker's own {@link CredentialStore} as secrets_user, under an ID derived from the
 *       business, so running again replaces the same credential;
 *   <li>the {@code integrations} row ({@code connected}, its last error cleared), which is what lets sends and the sync
 *       find the credential, with its audit entry: {@code integration.connected} for a new row,
 *       {@code integration.token_replaced} for one that was there.
 * </ol>
 *
 * <p>Sends paused earlier because the token had expired stay paused: resuming them on a reconnect is step 7.5. Nothing
 * here logs, and it never calls Meta.
 */
public final class WhatsAppNumberConnector implements AutoCloseable {

    static final String CONNECTED = "integration.connected";
    static final String TOKEN_REPLACED = "integration.token_replaced";
    static final String NUMBER_ADDED = "whatsapp_account.added";

    private static final String PROVIDER = "whatsapp";
    private static final Pattern META_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern DISPLAY_NUMBER = Pattern.compile("\\+?[0-9][0-9 ()-]{5,30}");

    private static final String NUMBER = """
            select id, waba_id from northstar.whatsapp_accounts where tenant_id = ? and phone_number_id = ?""";

    private static final String ADD_NUMBER = """
            insert into northstar.whatsapp_accounts (tenant_id, waba_id, phone_number_id, display_phone)
            values (?, ?, ?, ?) returning id""";

    private static final String UPDATE_DISPLAY_NUMBER = """
            update northstar.whatsapp_accounts set display_phone = ?
            where tenant_id = ? and id = ? and display_phone is distinct from ?""";

    private static final String INTEGRATION = """
            select id from northstar.integrations where tenant_id = ? and provider = 'whatsapp' for update""";

    private static final String ADD_INTEGRATION = """
            insert into northstar.integrations (tenant_id, provider, status, credential_id)
            values (?, 'whatsapp', 'connected', ?) returning id""";

    private static final String RECONNECT_INTEGRATION = """
            update northstar.integrations
            set status = 'connected', credential_id = ?, last_error_code = null, last_error_at = null
            where tenant_id = ? and id = ?""";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final TenantDirectory tenants;
    private final AuditTrail audit;
    private final CredentialStore credentials;

    WhatsAppNumberConnector(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            TenantDirectory tenants,
            AuditTrail audit,
            CredentialStore credentials) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.tenants = tenants;
        this.audit = audit;
        this.credentials = credentials;
    }

    /**
     * The connector on the tool's app context, with its own credential store: built by the worker's configuration from
     * the worker's settings, as secrets_user, and closed with the connector.
     */
    public static WhatsAppNumberConnector open(ApplicationContext context) {
        Environment settings = context.getEnvironment();
        CredentialStore credentials = new CredentialsConfiguration()
                .credentialStore(
                        settings.getProperty("northstar.credentials.keys", ""),
                        settings.getProperty("northstar.credentials.active-key", ""),
                        settings.getProperty("northstar.credentials.database.url", ""),
                        settings.getProperty("northstar.credentials.database.username", ""),
                        settings.getProperty("northstar.credentials.database.password", ""),
                        1);
        return new WhatsAppNumberConnector(
                context.getBean(JdbcClient.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(TenantDirectory.class),
                context.getBean(AuditTrail.class),
                credentials);
    }

    /**
     * What to connect. Meta's IDs and the business's own number: not secrets, but never logged (toString shows none).
     */
    public record Request(UUID tenantId, String phoneNumberId, String wabaId, String displayNumber) {

        public Request {
            Objects.requireNonNull(tenantId, "tenantId");
            require(META_ID, phoneNumberId, "--phone-number-id is Meta's phone number ID: up to 64 letters and digits");
            require(META_ID, wabaId, "--waba-id is the WhatsApp Business Account ID: up to 64 letters and digits");
            require(DISPLAY_NUMBER, displayNumber, "--display-number is the business's number, such as +15550001234");
        }

        private static void require(Pattern pattern, String value, String what) {
            if (value == null || !pattern.matcher(value).matches()) {
                throw new LocalToolException("argument_invalid", what + ".");
            }
        }

        @Override
        public String toString() {
            return "Request[tenantId=" + tenantId + "]";
        }
    }

    /** What a run did. */
    public record Connected(UUID accountId, UUID integrationId, boolean numberAdded, boolean tokenReplaced) {}

    private record Number(UUID id, boolean added) {}

    /** Connects the number. The token's bytes are this method's from here: they are wiped whatever happens. */
    public Connected connect(Request request, byte[] token) {
        Objects.requireNonNull(request, "request");
        try (Secret secret = Secret.wrap(token)) {
            return SystemScope.call(request.tenantId(), () -> {
                if (!tenants.currentTenantExists()) {
                    throw new LocalToolException("unknown_tenant", "No business has this ID. Nothing was done.");
                }
                Number number = saveNumber(request);
                UUID credentialId = credentialId(request.tenantId());
                boolean stored = credentials.insertIfAbsent(credentialId, PROVIDER, secret, null);
                if (!stored && !credentials.replace(credentialId, secret, null)) {
                    throw new IllegalStateException("The credential that blocked the insert is gone");
                }
                return saveIntegration(request.tenantId(), number, credentialId, !stored);
            });
        }
    }

    /** One credential per business for WhatsApp, under an ID that is the same on every run. */
    static UUID credentialId(UUID tenantId) {
        return UUID.nameUUIDFromBytes(("gate-whatsapp-credential:v1|" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private Number saveNumber(Request request) {
        try {
            return transactions.execute(status -> {
                record Existing(UUID id, String wabaId) {}
                Optional<Existing> existing = jdbc.sql(NUMBER)
                        .param(request.tenantId())
                        .param(request.phoneNumberId())
                        .query((row, n) -> new Existing(row.getObject("id", UUID.class), row.getString("waba_id")))
                        .optional();
                if (existing.isEmpty()) {
                    UUID added = jdbc.sql(ADD_NUMBER)
                            .param(request.tenantId())
                            .param(request.wabaId())
                            .param(request.phoneNumberId())
                            .param(request.displayNumber())
                            .query(UUID.class)
                            .single();
                    audit.record(NUMBER_ADDED, "whatsapp_accounts", added);
                    return new Number(added, true);
                }
                if (!existing.get().wabaId().equals(request.wabaId())) {
                    throw new LocalToolException(
                            "waba_mismatch",
                            "This number is already connected to this business under another WhatsApp Business Account."
                                    + " Nothing was done.");
                }
                jdbc.sql(UPDATE_DISPLAY_NUMBER)
                        .param(request.displayNumber())
                        .param(request.tenantId())
                        .param(existing.get().id())
                        .param(request.displayNumber())
                        .update();
                return new Number(existing.get().id(), false);
            });
        } catch (DataIntegrityViolationException taken) {
            // The number is unique across businesses and a WABA belongs to one of them (data-model.md section 9); RLS
            // hides
            // whose it is, and so does this message (P9).
            throw new LocalToolException(
                    "number_taken",
                    "This number, or its WhatsApp Business Account, is already connected to another business."
                            + " Nothing was done.");
        }
    }

    private Connected saveIntegration(UUID tenant, Number number, UUID credentialId, boolean tokenReplaced) {
        return transactions.execute(status -> {
            Optional<UUID> existing =
                    jdbc.sql(INTEGRATION).param(tenant).query(UUID.class).optional();
            UUID integration;
            if (existing.isPresent()) {
                integration = existing.get();
                jdbc.sql(RECONNECT_INTEGRATION)
                        .param(credentialId)
                        .param(tenant)
                        .param(integration)
                        .update();
            } else {
                integration = jdbc.sql(ADD_INTEGRATION)
                        .param(tenant)
                        .param(credentialId)
                        .query(UUID.class)
                        .single();
            }
            audit.record(existing.isPresent() ? TOKEN_REPLACED : CONNECTED, "integrations", integration);
            return new Connected(number.id(), integration, number.added(), tokenReplaced);
        });
    }

    /** Closes the credential store's pool. */
    @Override
    public void close() {
        try {
            credentials.close();
        } catch (Exception e) {
            throw new IllegalStateException("The credential store did not close", e);
        }
    }

    @Override
    public String toString() {
        return "WhatsAppNumberConnector";
    }
}
