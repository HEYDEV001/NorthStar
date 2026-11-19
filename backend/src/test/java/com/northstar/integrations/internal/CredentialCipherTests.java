package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * AES-256-GCM for tenant credentials (step 2.1b, integration-specs.md section 7): round trip, a fresh nonce every time,
 * tamper detection at every byte, the wrong or an unknown key, rotation, and failures that name IDs only.
 *
 * <p>The associated-data tests are mutation checks: each part of the binding (tenant, credential, provider, key ID) is
 * changed on its own and must fail, so dropping any one of them from the associated data fails a test; the documented
 * associated data rebuilt here by hand must decrypt our output with plain JCE, so changing its format fails a test; and
 * a control shows that without associated data the same swap would have decrypted.
 */
class CredentialCipherTests {

    private static final String PLAINTEXT = "EAAB-business-token-marker-91ab";
    private static final String K1 = CredentialKeyringTests.randomKey(32);
    private static final String K2 = CredentialKeyringTests.randomKey(32);

    private final CredentialCipher cipher = new CredentialCipher(CredentialKeyring.parse("k1:" + K1, "k1"));
    private final CredentialCipher.Binding binding =
            new CredentialCipher.Binding(UUID.randomUUID(), UUID.randomUUID(), "whatsapp");

    // Round trip and stored form.

    @Test
    void aValueDecryptsBackUnderItsBinding() {
        CredentialCipher.Sealed sealed = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT));

        try (Secret back = cipher.decrypt(binding, sealed.keyId(), sealed.encryptedSecret())) {
            assertThat(back.utf8()).isEqualTo(PLAINTEXT);
        }
    }

    @Test
    void theStoredFormIsFormatByteNonceCiphertextAndTagUnderTheActiveKeyId() {
        CredentialCipher.Sealed sealed = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT));
        byte[] stored = sealed.encryptedSecret();

        assertThat(sealed.keyId()).isEqualTo("k1");
        assertThat(stored[0]).isEqualTo(CredentialCipher.FORMAT_V1);
        assertThat(stored).hasSize(1 + 12 + PLAINTEXT.length() + 16);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("marker");
        assertThat(sealed.toString()).isEqualTo("Sealed[keyId=k1, bytes=" + stored.length + "]");
    }

    @Test
    void everyEncryptionUsesAFreshNonce() {
        Set<String> nonces = new HashSet<>();
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();
            nonces.add(HexFormat.of().formatHex(stored, 1, 13));
            ciphertexts.add(HexFormat.of().formatHex(stored));
        }

        assertThat(nonces).as("no nonce repeats").hasSize(1000);
        assertThat(ciphertexts)
                .as("the same plaintext never encrypts the same way twice")
                .hasSize(1000);
    }

    // Tampering.

    @Test
    void flippingAnyByteIsDetected() {
        byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();

        for (int position = 0; position < stored.length; position++) {
            byte[] tampered = stored.clone();
            tampered[position] ^= 0x01;
            String part = position == 0 ? "format byte" : position <= 12 ? "nonce" : "ciphertext or tag";
            String expected = position == 0 ? "unsupported_format" : "authentication_failed";
            assertThatThrownBy(() -> cipher.decrypt(binding, "k1", tampered))
                    .as("byte " + position + " (" + part + ")")
                    .isInstanceOfSatisfying(
                            CredentialDecryptionException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(expected));
        }
    }

    @Test
    void truncatedOrExtendedValuesAreDetected() {
        byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();

        assertFailsWith(cipher, binding, "k1", Arrays.copyOf(stored, stored.length - 1), "authentication_failed");
        assertFailsWith(cipher, binding, "k1", Arrays.copyOf(stored, stored.length + 1), "authentication_failed");
        assertFailsWith(cipher, binding, "k1", Arrays.copyOf(stored, 29), "truncated");
        assertFailsWith(cipher, binding, "k1", new byte[0], "truncated");
        assertFailsWith(cipher, binding, "k1", null, "truncated");
    }

    // Keys.

    @Test
    void theWrongKeyUnderTheSameIdFails() {
        byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();
        CredentialCipher otherKey = new CredentialCipher(CredentialKeyring.parse("k1:" + K2, "k1"));

        assertFailsWith(otherKey, binding, "k1", stored, "authentication_failed");
    }

    @Test
    void aKeyIdTheKeyringDoesNotHaveFails() {
        byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();

        assertFailsWith(cipher, binding, "k9", stored, "unknown_key");
    }

    @Test
    void afterRotationOldValuesStillDecryptAndNewOnesUseTheNewKey() {
        byte[] old = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();
        CredentialCipher rotated = new CredentialCipher(CredentialKeyring.parse("k1:" + K1 + ",k2:" + K2, "k2"));

        CredentialCipher.Sealed fresh = rotated.encrypt(binding, Secret.ofUtf8(PLAINTEXT));

        try (Secret back = rotated.decrypt(binding, "k1", old)) {
            assertThat(back.utf8()).isEqualTo(PLAINTEXT);
        }
        assertThat(fresh.keyId()).isEqualTo("k2");
        assertFailsWith(cipher, binding, "k2", fresh.encryptedSecret(), "unknown_key");
    }

    // The binding: mutation checks on the associated data.

    @Test
    void aValueMovedToAnotherTenantRowOrProviderFails() {
        byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();

        for (CredentialCipher.Binding moved : List.of(
                new CredentialCipher.Binding(UUID.randomUUID(), binding.credentialId(), binding.provider()),
                new CredentialCipher.Binding(binding.tenantId(), UUID.randomUUID(), binding.provider()),
                new CredentialCipher.Binding(binding.tenantId(), binding.credentialId(), "razorpay"))) {
            assertFailsWith(cipher, moved, "k1", stored, "authentication_failed");
        }
    }

    @Test
    void aValueRelabelledWithAnotherKeyIdFailsEvenWithTheSameKeyBytes() {
        // Two IDs for the same bytes: only the key ID in the associated data tells them apart.
        CredentialCipher twins = new CredentialCipher(CredentialKeyring.parse("k1:" + K1 + ",k1-copy:" + K1, "k1"));
        byte[] stored = twins.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();

        assertFailsWith(twins, binding, "k1-copy", stored, "authentication_failed");
        try (Secret back = twins.decrypt(binding, "k1", stored)) {
            assertThat(back.utf8()).isEqualTo(PLAINTEXT);
        }
    }

    @Test
    void theAssociatedDataIsExactlyTheDocumentedOne() throws Exception {
        byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();
        String documented = "credential:v1|" + binding.tenantId() + "|" + binding.credentialId() + "|whatsapp|k1";

        assertThat(rawDecrypt(key(K1), stored, documented.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(PLAINTEXT);
        // Each part left out, or the version changed, is a different associated data: the tag fails.
        for (String variant : List.of(
                "credential:v1|" + binding.credentialId() + "|whatsapp|k1",
                "credential:v1|" + binding.tenantId() + "|whatsapp|k1",
                "credential:v1|" + binding.tenantId() + "|" + binding.credentialId() + "|k1",
                "credential:v1|" + binding.tenantId() + "|" + binding.credentialId() + "|whatsapp",
                "credential:v2|" + binding.tenantId() + "|" + binding.credentialId() + "|whatsapp|k1",
                "")) {
            assertThatThrownBy(() -> rawDecrypt(key(K1), stored, variant.getBytes(StandardCharsets.UTF_8)))
                    .as(variant.isEmpty() ? "no associated data" : variant)
                    .isInstanceOf(AEADBadTagException.class);
        }
    }

    @Test
    void controlWithoutAssociatedDataTheSameSwapWouldHaveDecrypted() throws Exception {
        // What the binding protects against: a value sealed with no associated data decrypts in any row.
        byte[] unbound = rawEncrypt(key(K1), PLAINTEXT, new byte[0]);
        assertThat(rawDecrypt(key(K1), unbound, new byte[0])).isEqualTo(PLAINTEXT);

        // Our cipher refuses such a value in every row, because it always checks the binding.
        assertFailsWith(cipher, binding, "k1", unbound, "authentication_failed");
    }

    // What failures say.

    @Test
    void failuresNameTheCredentialAndKeyOnlyAndCarryNoCause() {
        byte[] stored = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT)).encryptedSecret();
        byte[] tampered = stored.clone();
        tampered[stored.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(binding, "k1", tampered))
                .isInstanceOf(CredentialDecryptionException.class)
                .hasMessage("Credential " + binding.credentialId() + " could not be decrypted with key k1"
                        + " (authentication_failed)")
                .hasNoCause()
                .satisfies(failure -> {
                    SecretTests.assertNoTraceOf(PLAINTEXT, failure.toString());
                    assertThat(failure.toString())
                            .doesNotContain(K1)
                            .doesNotContain(HexFormat.of().formatHex(stored));
                });
    }

    @Test
    void anEncryptedValueAndTheCipherPrintNoKeyOrCiphertext() {
        CredentialCipher.Sealed sealed = cipher.encrypt(binding, Secret.ofUtf8(PLAINTEXT));

        for (String rendering : List.of(sealed.toString(), cipher.toString())) {
            assertThat(rendering)
                    .doesNotContain(K1)
                    .doesNotContain(HexFormat.of().formatHex(sealed.encryptedSecret()));
            SecretTests.assertNoTraceOf(PLAINTEXT, rendering);
        }
    }

    // Helpers.

    private static void assertFailsWith(
            CredentialCipher cipher, CredentialCipher.Binding binding, String keyId, byte[] stored, String reason) {
        assertThatThrownBy(() -> cipher.decrypt(binding, keyId, stored))
                .isInstanceOfSatisfying(
                        CredentialDecryptionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static SecretKey key(String encoded) {
        return new SecretKeySpec(java.util.Base64.getDecoder().decode(encoded), "AES");
    }

    /** Plain JCE, reading our stored form: format byte, 12-byte nonce, ciphertext and tag. */
    private static String rawDecrypt(SecretKey key, byte[] stored, byte[] associatedData) throws Exception {
        Cipher raw = Cipher.getInstance("AES/GCM/NoPadding");
        raw.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, stored, 1, 12));
        raw.updateAAD(associatedData);
        return new String(raw.doFinal(stored, 13, stored.length - 13), StandardCharsets.UTF_8);
    }

    /** Plain JCE, writing our stored form with the given associated data. */
    private static byte[] rawEncrypt(SecretKey key, String plaintext, byte[] associatedData) throws Exception {
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        Cipher raw = Cipher.getInstance("AES/GCM/NoPadding");
        raw.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        raw.updateAAD(associatedData);
        byte[] ciphertext = raw.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] stored = new byte[13 + ciphertext.length];
        stored[0] = CredentialCipher.FORMAT_V1;
        System.arraycopy(nonce, 0, stored, 1, 12);
        System.arraycopy(ciphertext, 0, stored, 13, ciphertext.length);
        return stored;
    }
}
