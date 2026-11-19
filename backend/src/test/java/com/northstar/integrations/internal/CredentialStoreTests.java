package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.jobs.SystemScope;
import com.northstar.tenancy.MissingTenantContextException;
import com.northstar.tenancy.Role;
import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The worker's credential store against real Postgres with RLS (step 2.1b): built by {@link CredentialsConfiguration}
 * exactly as the worker builds it, on its own pool as secrets_user. Only ciphertext reaches the database; each tenant
 * sees only its own credentials; only the system actor may call it; re-encryption moves one tenant's rows to the
 * active key; tampered or moved rows fail; and nothing secret is printed even with JDBC tracing on.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialStoreTests {

    private static final String PLAINTEXT = "EAAB-business-token-marker-91ab";
    private static final String K1 = CredentialKeyringTests.randomKey(32);
    private static final String K2 = CredentialKeyringTests.randomKey(32);

    private final JdbcClient admin;
    private final TestData data;
    private final PostgreSQLContainer container;
    private final TestcontainersConfiguration.TestLogins logins;
    private final List<CredentialStore> opened = new ArrayList<>();

    /** Keys k1 (active). */
    private final CredentialStore store;
    /** Keys k1 and k2, k2 active: after a rotation. */
    private final CredentialStore rotated;
    /** Key k2 only: once k1 has been removed. */
    private final CredentialStore k2Only;

    CredentialStoreTests(
            @Autowired PostgresAdmin postgres,
            @Autowired PostgreSQLContainer container,
            @Autowired TestcontainersConfiguration.TestLogins logins) {
        this.admin = postgres.jdbc();
        this.data = new TestData(postgres);
        this.container = container;
        this.logins = logins;
        this.store = open("k1:" + K1, "k1");
        this.rotated = open("k1:" + K1 + ",k2:" + K2, "k2");
        this.k2Only = open("k2:" + K2, "k2");
    }

    @AfterAll
    void closePools() throws Exception {
        for (CredentialStore each : opened) each.close();
    }

    @Test
    void aStoredCredentialReadsBackAndOnlyItsCiphertextReachesTheDatabase() {
        UUID tenant = data.tenant();
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

        boolean stored =
                asWorker(tenant, () -> store.insertIfAbsent(id, "whatsapp", Secret.ofUtf8(PLAINTEXT), expires));

        assertThat(stored).isTrue();
        assertThat(readUtf8(store, tenant, id)).contains(PLAINTEXT);
        StoredColumns row = storedColumns(id);
        assertThat(row.keyId()).isEqualTo("k1");
        assertThat(row.expiresAt()).isEqualTo(expires);
        assertThat(row.rotatedAt()).isNull();
        assertThat(row.encryptedSecret()[0]).isEqualTo(CredentialCipher.FORMAT_V1);
        assertThat(new String(row.encryptedSecret(), StandardCharsets.ISO_8859_1))
                .as("the plaintext is nowhere in the stored bytes")
                .doesNotContain(PLAINTEXT)
                .doesNotContain("marker");
        assertThat(HexFormat.of().formatHex(row.encryptedSecret()))
                .doesNotContain(HexFormat.of().formatHex(PLAINTEXT.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void storingTheSameIdTwiceKeepsTheFirstValue() {
        UUID tenant = data.tenant();
        UUID id = UUID.randomUUID();
        asWorker(tenant, () -> store.insertIfAbsent(id, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));

        boolean again = asWorker(tenant, () -> store.insertIfAbsent(id, "whatsapp", Secret.ofUtf8("other"), null));

        assertThat(again).isFalse();
        assertThat(readUtf8(store, tenant, id)).contains(PLAINTEXT);
        assertThat(admin.sql("select count(*) from secrets.integration_credentials where id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void anotherTenantsCredentialIsTheSameAsAMissingOne() {
        UUID owner = data.tenant();
        UUID outsider = data.tenant();
        UUID id = UUID.randomUUID();
        asWorker(owner, () -> store.insertIfAbsent(id, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));

        assertThat(readUtf8(store, outsider, id)).isEmpty();
        assertThat(readUtf8(store, outsider, UUID.randomUUID())).isEmpty();
        assertThat(asWorker(outsider, () -> store.replace(id, Secret.ofUtf8("stolen"), null)))
                .isFalse();
        assertThat(asWorker(outsider, rotated::reencryptUnderActiveKey)).isZero();
        assertThat(readUtf8(store, owner, id)).contains(PLAINTEXT);
    }

    @Test
    void onlyTheSystemActorMayUseIt() {
        TestData.Member member = data.member("admin");
        UUID id = UUID.randomUUID();
        asWorker(member.tenantId(), () -> store.insertIfAbsent(id, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));

        assertThatThrownBy(() -> store.read(id))
                .as("no tenant context")
                .isInstanceOf(MissingTenantContextException.class);
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN), () -> store.read(id)))
                .as("a business admin, as on an API thread")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant credentials are used only by the worker's system actor");
        assertThatThrownBy(() -> TenantScope.call(
                        new TenantContext(member.tenantId(), member.userId(), Role.ADMIN),
                        () -> store.insertIfAbsent(UUID.randomUUID(), "whatsapp", Secret.ofUtf8(PLAINTEXT), null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replacingAValueUsesTheActiveKeyAndSetsRotatedAt() {
        UUID tenant = data.tenant();
        UUID id = UUID.randomUUID();
        asWorker(tenant, () -> store.insertIfAbsent(id, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));

        boolean replaced = asWorker(tenant, () -> rotated.replace(id, Secret.ofUtf8("EAAB-refreshed-token"), null));

        assertThat(replaced).isTrue();
        assertThat(storedColumns(id).keyId()).isEqualTo("k2");
        assertThat(storedColumns(id).rotatedAt()).isNotNull();
        assertThat(readUtf8(k2Only, tenant, id)).contains("EAAB-refreshed-token");
    }

    @Test
    void reencryptionMovesOneTenantsOldValuesToTheActiveKey() {
        UUID tenant = data.tenant();
        UUID other = data.tenant();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID othersCredential = UUID.randomUUID();
        asWorker(tenant, () -> store.insertIfAbsent(first, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));
        asWorker(tenant, () -> store.insertIfAbsent(second, "razorpay", Secret.ofUtf8("rzp_test_key"), null));
        asWorker(other, () -> store.insertIfAbsent(othersCredential, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));

        int moved = asWorker(tenant, rotated::reencryptUnderActiveKey);
        int again = asWorker(tenant, rotated::reencryptUnderActiveKey);

        assertThat(moved).isEqualTo(2);
        assertThat(again).as("a second run finds nothing to do").isZero();
        assertThat(storedColumns(first).keyId()).isEqualTo("k2");
        assertThat(storedColumns(first).rotatedAt()).isNotNull();
        assertThat(readUtf8(k2Only, tenant, first)).contains(PLAINTEXT);
        assertThat(readUtf8(k2Only, tenant, second)).contains("rzp_test_key");
        assertThat(storedColumns(othersCredential).keyId())
                .as("another tenant's rows are untouched")
                .isEqualTo("k1");
        assertThatThrownBy(() -> readUtf8(k2Only, other, othersCredential))
                .as("until its own re-encryption, the old key is still needed")
                .isInstanceOfSatisfying(
                        CredentialDecryptionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo("unknown_key"));
    }

    @Test
    void aTamperedRowOrAValueCopiedToAnotherTenantFailsToDecrypt() {
        UUID owner = data.tenant();
        UUID thief = data.tenant();
        UUID ownersCredential = UUID.randomUUID();
        UUID thiefsCredential = UUID.randomUUID();
        asWorker(owner, () -> store.insertIfAbsent(ownersCredential, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));
        asWorker(thief, () -> store.insertIfAbsent(thiefsCredential, "whatsapp", Secret.ofUtf8("own-token"), null));

        // Someone with database access copies the owner's ciphertext into their own row, same provider and key.
        admin.sql("""
                        update secrets.integration_credentials
                        set encrypted_secret = (select encrypted_secret from secrets.integration_credentials
                                                where id = :from)
                        where id = :to""")
                .param("from", ownersCredential)
                .param("to", thiefsCredential)
                .update();
        assertThatThrownBy(() -> readUtf8(store, thief, thiefsCredential))
                .isInstanceOfSatisfying(
                        CredentialDecryptionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo("authentication_failed"));

        // Or flips one bit of a stored value.
        admin.sql("""
                        update secrets.integration_credentials
                        set encrypted_secret = set_byte(encrypted_secret, 20, get_byte(encrypted_secret, 20) # 1)
                        where id = :id""").param("id", ownersCredential).update();
        assertThatThrownBy(() -> readUtf8(store, owner, ownersCredential))
                .isInstanceOfSatisfying(
                        CredentialDecryptionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo("authentication_failed"));
    }

    @Test
    void nothingSecretIsPrintedEvenWithJdbcTracingOn(CapturedOutput output) throws Exception {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        List<String> loggers =
                List.of("org.springframework.jdbc", "org.springframework.transaction", "com.zaxxer.hikari");
        loggers.forEach(logger -> logging.setLogLevel(logger, LogLevel.TRACE));
        try {
            UUID tenant = data.tenant();
            UUID id = UUID.randomUUID();
            try (CredentialStore traced = open("k1:" + K1 + ",k2:" + K2, "k1")) {
                opened.remove(traced);
                asWorker(tenant, () -> traced.insertIfAbsent(id, "whatsapp", Secret.ofUtf8(PLAINTEXT), null));
                assertThat(readUtf8(traced, tenant, id)).contains(PLAINTEXT);
                asWorker(tenant, () -> traced.replace(id, Secret.ofUtf8(PLAINTEXT), null));
                asWorker(tenant, rotated::reencryptUnderActiveKey);
                admin.sql("update secrets.integration_credentials set encrypted_secret = set_byte(encrypted_secret,"
                                + " 20, get_byte(encrypted_secret, 20) # 1) where id = :id")
                        .param("id", id)
                        .update();
                assertThatThrownBy(() -> readUtf8(traced, tenant, id))
                        .isInstanceOf(CredentialDecryptionException.class);
            }
        } finally {
            loggers.forEach(logger -> logging.setLogLevel(logger, null));
        }

        String printed = output.getAll();
        assertThat(printed).as("the tracing was on").contains("secrets.integration_credentials");
        SecretTests.assertNoTraceOf(PLAINTEXT, printed);
        assertThat(printed).doesNotContain(K1).doesNotContain(K2).doesNotContain(logins.secretsUserPassword());
    }

    // Helpers.

    private CredentialStore open(String keys, String activeKey) {
        CredentialStore opening = new CredentialsConfiguration()
                .credentialStore(
                        keys, activeKey, container.getJdbcUrl(), "secrets_user", logins.secretsUserPassword(), 2);
        opened.add(opening);
        return opening;
    }

    private static <T> T asWorker(UUID tenant, java.util.function.Supplier<T> work) {
        return SystemScope.call(tenant, work);
    }

    /** The decrypted value as text, closing the secret at once. */
    private static Optional<String> readUtf8(CredentialStore from, UUID tenant, UUID id) {
        return asWorker(
                tenant,
                () -> from.read(id).map(secret -> {
                    try (secret) {
                        return secret.utf8();
                    }
                }));
    }

    private record StoredColumns(byte[] encryptedSecret, String keyId, Instant expiresAt, Instant rotatedAt) {}

    private StoredColumns storedColumns(UUID id) {
        return admin.sql("""
                        select encrypted_secret, key_id, expires_at, rotated_at
                        from secrets.integration_credentials where id = :id""")
                .param("id", id)
                .query((row, n) -> new StoredColumns(
                        row.getBytes("encrypted_secret"),
                        row.getString("key_id"),
                        instant(row.getObject("expires_at", java.time.OffsetDateTime.class)),
                        instant(row.getObject("rotated_at", java.time.OffsetDateTime.class))))
                .single();
    }

    private static Instant instant(java.time.OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
