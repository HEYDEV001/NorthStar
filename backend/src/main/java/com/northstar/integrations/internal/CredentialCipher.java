package com.northstar.integrations.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts tenant credentials with AES-256-GCM from the JDK (integration-specs.md section 7; no new dependency).
 *
 * <p>Stored form: a format byte ({@code 0x01}), a fresh random 96-bit nonce from {@link SecureRandom}, then the
 * ciphertext and its 128-bit tag. The key ID is stored next to it, in its own column. The associated data is
 * {@code credential:v1|tenant_id|credential_id|provider|key_id}, so the tag covers which row, tenant and provider the
 * value belongs to and which key ID it claims: a value copied to another row, tenant or provider, or relabelled with
 * another key ID, fails to decrypt even when the key bytes are the same.
 *
 * <p>Failures say only which credential and key ID failed, with a fixed reason code, never why in cipher terms.
 */
final class CredentialCipher {

    static final byte FORMAT_V1 = 0x01;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    static final int TAG_BYTES = TAG_BITS / 8;
    static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Format byte, nonce, at least one byte of ciphertext, tag. */
    static final int MIN_STORED_BYTES = 1 + NONCE_BYTES + 1 + TAG_BYTES;

    /** Which credential a value belongs to: the parts of the associated data that come from the row. */
    record Binding(UUID tenantId, UUID credentialId, String provider) {
        Binding {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(credentialId, "credentialId");
            Objects.requireNonNull(provider, "provider");
        }
    }

    /** What is stored: the bytes for encrypted_secret and the ID of the key that made them. */
    record Sealed(byte[] encryptedSecret, String keyId) {
        @Override
        public String toString() {
            return "Sealed[keyId=" + keyId + ", bytes=" + encryptedSecret.length + "]";
        }
    }

    private final CredentialKeyring keyring;
    private final SecureRandom random;

    CredentialCipher(CredentialKeyring keyring) {
        this(keyring, new SecureRandom());
    }

    CredentialCipher(CredentialKeyring keyring, SecureRandom random) {
        this.keyring = Objects.requireNonNull(keyring, "keyring");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Encrypts under the active key. */
    Sealed encrypt(Binding binding, Secret secret) {
        String keyId = keyring.activeKeyId();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = secret.use(plaintext -> {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, keyring.activeKey(), new GCMParameterSpec(TAG_BITS, nonce));
                cipher.updateAAD(associatedData(binding, keyId));
                return cipher.doFinal(plaintext);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM encryption failed for credential " + binding.credentialId());
            }
        });
        byte[] stored = new byte[1 + NONCE_BYTES + ciphertext.length];
        stored[0] = FORMAT_V1;
        System.arraycopy(nonce, 0, stored, 1, NONCE_BYTES);
        System.arraycopy(ciphertext, 0, stored, 1 + NONCE_BYTES, ciphertext.length);
        return new Sealed(stored, keyId);
    }

    /** Decrypts a stored value with the key its row names. The caller closes the returned secret. */
    Secret decrypt(Binding binding, String keyId, byte[] stored) {
        if (stored == null || stored.length < MIN_STORED_BYTES) throw failure(binding, keyId, "truncated");
        if (stored[0] != FORMAT_V1) throw failure(binding, keyId, "unsupported_format");
        SecretKey key = keyring.key(keyId).orElseThrow(() -> failure(binding, keyId, "unknown_key"));
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            cipher.updateAAD(associatedData(binding, keyId));
            int offset = 1 + NONCE_BYTES;
            return Secret.wrap(cipher.doFinal(stored, offset, stored.length - offset));
        } catch (AEADBadTagException tampered) {
            throw failure(binding, keyId, "authentication_failed");
        } catch (GeneralSecurityException e) {
            throw failure(binding, keyId, "cipher_error");
        }
    }

    String activeKeyId() {
        return keyring.activeKeyId();
    }

    @Override
    public String toString() {
        return "CredentialCipher[" + keyring + "]";
    }

    static byte[] associatedData(Binding binding, String keyId) {
        return ("credential:v1|" + binding.tenantId() + "|" + binding.credentialId() + "|" + binding.provider() + "|"
                        + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static CredentialDecryptionException failure(Binding binding, String keyId, String reason) {
        return new CredentialDecryptionException(binding.credentialId(), keyId, reason);
    }
}
