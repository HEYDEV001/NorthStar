package com.northstar.integrations.internal;

import java.util.UUID;

/**
 * A stored credential could not be decrypted: tampered or truncated bytes, a value moved to another row, tenant or
 * provider, the wrong key under its key ID, or a key ID the keyring no longer has. The message names the credential,
 * the key ID and a fixed reason code only, and no cause is attached, so nothing of the key, the ciphertext or the
 * plaintext reaches a log or an error.
 */
final class CredentialDecryptionException extends RuntimeException {

    private final String reason;

    CredentialDecryptionException(UUID credentialId, String keyId, String reason) {
        super("Credential " + credentialId + " could not be decrypted with key " + keyId + " (" + reason + ")");
        this.reason = reason;
    }

    /** truncated, unsupported_format, unknown_key, authentication_failed or cipher_error. */
    String reason() {
        return reason;
    }
}
