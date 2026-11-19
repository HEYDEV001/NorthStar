package com.northstar.integrations.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant credentials in secrets.integration_credentials (data-model.md section 9, integration-specs.md section 7),
 * read and written only in the worker.
 *
 * <p>It runs on its own private pool, logged in as secrets_user, the only role that may use the secrets schema; the
 * pool is not a bean, so nothing else can reach it, and the API never builds this class (see
 * {@link CredentialsConfiguration}). Every call needs the worker's system actor in {@link TenantScope}: without a
 * context it throws {@link com.northstar.tenancy.MissingTenantContextException}, and any other role is refused before a
 * connection is taken. Each call is one transaction that first sets app.tenant_id, app.user_id and app.role, so the
 * table's tenant_isolation policy applies, and every query also filters on the tenant.
 *
 * <p>Only ciphertext and the key ID reach the database. Nothing here logs.
 */
final class CredentialStore implements AutoCloseable {

    private final DataSource pool;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final CredentialCipher cipher;

    CredentialStore(DataSource pool, CredentialCipher cipher) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.jdbc = JdbcClient.create(pool);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    /**
     * Stores a new credential under the given ID, encrypted with the active key. The ID is chosen by the caller (an
     * action can derive it), so running the same work twice stores one row: a second call with the same ID changes
     * nothing and returns false.
     */
    boolean insertIfAbsent(UUID credentialId, String provider, Secret secret, Instant expiresAt) {
        return inWorkerScope(context -> {
            CredentialCipher.Sealed sealed =
                    cipher.encrypt(new CredentialCipher.Binding(context.tenantId(), credentialId, provider), secret);
            return jdbc.sql("""
                            insert into secrets.integration_credentials
                              (id, tenant_id, provider, encrypted_secret, key_id, expires_at)
                            values (:id, :tenant, :provider, :bytes, :key, cast(:expires as timestamptz))
                            on conflict (id) do nothing""")
                            .param("id", credentialId)
                            .param("tenant", context.tenantId())
                            .param("provider", provider)
                            .param("bytes", sealed.encryptedSecret())
                            .param("key", sealed.keyId())
                            .param("expires", utc(expiresAt))
                            .update()
                    == 1;
        });
    }

    /**
     * The decrypted credential, or empty when the current tenant has none with this ID (another tenant's is the same
     * as a missing one). The caller closes the secret. A value that fails to decrypt throws
     * {@link CredentialDecryptionException}.
     */
    Optional<Secret> read(UUID credentialId) {
        return inWorkerScope(context -> jdbc.sql("""
                        select provider, key_id, encrypted_secret from secrets.integration_credentials
                        where id = :id and tenant_id = :tenant""")
                .param("id", credentialId)
                .param("tenant", context.tenantId())
                .query((row, n) -> cipher.decrypt(
                        new CredentialCipher.Binding(context.tenantId(), credentialId, row.getString("provider")),
                        row.getString("key_id"),
                        row.getBytes("encrypted_secret")))
                .optional());
    }

    /**
     * Replaces the value (a refreshed or reconnected token), encrypted with the active key, and sets rotated_at. False
     * when the current tenant has no credential with this ID.
     */
    boolean replace(UUID credentialId, Secret secret, Instant expiresAt) {
        return inWorkerScope(context -> {
            Optional<String> provider = lockedProvider(context, credentialId);
            if (provider.isEmpty()) return false;
            CredentialCipher.Sealed sealed = cipher.encrypt(
                    new CredentialCipher.Binding(context.tenantId(), credentialId, provider.get()), secret);
            return jdbc.sql("""
                            update secrets.integration_credentials
                            set encrypted_secret = :bytes, key_id = :key,
                                expires_at = cast(:expires as timestamptz), rotated_at = now()
                            where id = :id and tenant_id = :tenant""")
                            .param("bytes", sealed.encryptedSecret())
                            .param("key", sealed.keyId())
                            .param("expires", utc(expiresAt))
                            .param("id", credentialId)
                            .param("tenant", context.tenantId())
                            .update()
                    == 1;
        });
    }

    /**
     * Re-encrypts every credential of the current tenant that another key made, under the active key, and sets their
     * rotated_at (key rotation, integration-specs.md section 7). One transaction: a value that fails to decrypt rolls
     * the whole tenant back and throws. Returns how many rows moved; a second run returns 0.
     */
    int reencryptUnderActiveKey() {
        return inWorkerScope(context -> {
            String active = cipher.activeKeyId();
            List<StoredRow> stale = jdbc.sql("""
                            select id, provider, key_id, encrypted_secret from secrets.integration_credentials
                            where tenant_id = :tenant and key_id <> :active
                            order by id
                            for update""")
                    .param("tenant", context.tenantId())
                    .param("active", active)
                    .query((row, n) -> new StoredRow(
                            row.getObject("id", UUID.class),
                            row.getString("provider"),
                            row.getString("key_id"),
                            row.getBytes("encrypted_secret")))
                    .list();
            for (StoredRow row : stale) {
                CredentialCipher.Binding binding =
                        new CredentialCipher.Binding(context.tenantId(), row.id(), row.provider());
                CredentialCipher.Sealed sealed;
                try (Secret secret = cipher.decrypt(binding, row.keyId(), row.encryptedSecret())) {
                    sealed = cipher.encrypt(binding, secret);
                }
                jdbc.sql("""
                                update secrets.integration_credentials
                                set encrypted_secret = :bytes, key_id = :key, rotated_at = now()
                                where id = :id and tenant_id = :tenant""")
                        .param("bytes", sealed.encryptedSecret())
                        .param("key", sealed.keyId())
                        .param("id", row.id())
                        .param("tenant", context.tenantId())
                        .update();
            }
            return stale.size();
        });
    }

    @Override
    public void close() throws Exception {
        if (pool instanceof AutoCloseable closeable) closeable.close();
    }

    @Override
    public String toString() {
        return "CredentialStore[" + cipher + "]";
    }

    private record StoredRow(UUID id, String provider, String keyId, byte[] encryptedSecret) {
        @Override
        public String toString() {
            return "StoredRow[id=" + id + ", keyId=" + keyId + "]";
        }
    }

    private Optional<String> lockedProvider(TenantContext context, UUID credentialId) {
        return jdbc.sql("""
                        select provider from secrets.integration_credentials
                        where id = :id and tenant_id = :tenant
                        for update""")
                .param("id", credentialId)
                .param("tenant", context.tenantId())
                .query(String.class)
                .optional();
    }

    /** Requires the worker's system actor, then runs the work in one transaction with the tenant context set. */
    private <T> T inWorkerScope(Function<TenantContext, T> work) {
        TenantContext context = TenantScope.require();
        if (!context.isSystem()) {
            throw new IllegalStateException("Tenant credentials are used only by the worker's system actor");
        }
        return transactions.execute(status -> {
            jdbc.sql("""
                            select set_config('app.tenant_id', :tenant, true),
                                   set_config('app.user_id', :user, true),
                                   set_config('app.role', :role, true)""")
                    .param("tenant", context.tenantId().toString())
                    .param("user", context.userId().toString())
                    .param("role", context.role().databaseValue())
                    .query()
                    .listOfRows();
            return work.apply(context);
        });
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
