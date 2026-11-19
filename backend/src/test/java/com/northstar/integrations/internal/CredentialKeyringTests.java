package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The worker's keyring settings (step 2.1b, integration-specs.md section 7): what parses, and that every refusal names
 * the setting, the entry's position or a valid key ID, never the key material itself.
 */
class CredentialKeyringTests {

    private static final String K1 = randomKey(32);
    private static final String K2 = randomKey(32);

    @Test
    void parsesSeveralKeysAndPicksTheActiveOne() {
        CredentialKeyring keyring = CredentialKeyring.parse(" k1:" + K1 + " , k2-2026:" + K2 + " ", " k2-2026 ");

        assertThat(keyring.keyIds()).containsExactly("k1", "k2-2026");
        assertThat(keyring.activeKeyId()).isEqualTo("k2-2026");
        assertThat(keyring.activeKey().getEncoded())
                .isEqualTo(Base64.getDecoder().decode(K2));
        assertThat(keyring.key("k1").orElseThrow().getEncoded())
                .isEqualTo(Base64.getDecoder().decode(K1));
        assertThat(keyring.key("k3")).isEmpty();
        assertThat(keyring.activeKey().getAlgorithm()).isEqualTo("AES");
    }

    @Test
    void itsToStringShowsKeyIdsOnly() {
        CredentialKeyring keyring = CredentialKeyring.parse("k1:" + K1 + ",k2:" + K2, "k2");

        assertThat(keyring.toString()).isEqualTo("CredentialKeyring[active=k2, keys=[k1, k2]]");
        assertThat(new CredentialCipher(keyring).toString()).doesNotContain(K1).doesNotContain(K2);
    }

    @Test
    void missingSettingsAreRefusedByName() {
        assertRefused(null, "k1", "NORTHSTAR_CREDENTIAL_KEYS is required when the worker is on");
        assertRefused(" ", "k1", "NORTHSTAR_CREDENTIAL_KEYS is required when the worker is on");
        assertRefused("k1:" + K1, null, "NORTHSTAR_CREDENTIAL_ACTIVE_KEY is required when the worker is on");
        assertRefused("k1:" + K1, "", "NORTHSTAR_CREDENTIAL_ACTIVE_KEY is required when the worker is on");
    }

    @Test
    void malformedEntriesAreRefusedWithoutShowingTheKey() {
        // A key pasted without its ID: the whole entry is key material.
        assertRefused(K1, "k1", "NORTHSTAR_CREDENTIAL_KEYS entry 1 is not keyId:base64");
        assertRefused("k1:" + K1 + ",", "k1", "NORTHSTAR_CREDENTIAL_KEYS entry 2 is not keyId:base64");
        // A key pasted where the ID goes.
        assertRefused(
                K2 + ":" + K1,
                "k1",
                "NORTHSTAR_CREDENTIAL_KEYS entry 1 has an invalid key ID (lowercase letters, digits and hyphens, at"
                        + " most 32)");
        assertRefused(
                "K1:" + K1,
                "k1",
                "NORTHSTAR_CREDENTIAL_KEYS entry 1 has an invalid key ID (lowercase letters, digits and hyphens, at"
                        + " most 32)");
        assertRefused("k1:" + K1 + ",k1:" + K2, "k1", "NORTHSTAR_CREDENTIAL_KEYS names key k1 twice");
        assertRefused("k1:" + K1.replace('A', '*') + "*", "k1", "NORTHSTAR_CREDENTIAL_KEYS key k1 is not base64");
    }

    @Test
    void aKeyMustBeExactly32Bytes() {
        for (int size : new int[] {16, 31, 33, 64}) {
            assertRefused("k1:" + randomKey(size), "k1", "NORTHSTAR_CREDENTIAL_KEYS key k1 must be exactly 32 bytes");
        }
    }

    @Test
    void theActiveKeyMustBeOneOfTheKeys() {
        assertRefused("k1:" + K1, "k2", "NORTHSTAR_CREDENTIAL_ACTIVE_KEY must name a key in NORTHSTAR_CREDENTIAL_KEYS");
        // The key itself pasted as the active ID is not echoed back.
        assertRefused("k1:" + K1, K1, "NORTHSTAR_CREDENTIAL_ACTIVE_KEY must name a key in NORTHSTAR_CREDENTIAL_KEYS");
    }

    private static void assertRefused(String keys, String active, String message) {
        assertThatThrownBy(() -> CredentialKeyring.parse(keys, active))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(message)
                .hasNoCause()
                .satisfies(refused -> assertThat(refused.getMessage())
                        .doesNotContain(K1)
                        .doesNotContain(K2)
                        .doesNotContain(K1.substring(0, 12))
                        .doesNotContain(K2.substring(0, 12)));
    }

    static String randomKey(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
