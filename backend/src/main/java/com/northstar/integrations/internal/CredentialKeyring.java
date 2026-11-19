package com.northstar.integrations.internal;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The worker's credential keys (integration-specs.md section 7): {@code NORTHSTAR_CREDENTIAL_KEYS} holds
 * comma-separated {@code keyId:base64} entries, each exactly 32 random bytes, and
 * {@code NORTHSTAR_CREDENTIAL_ACTIVE_KEY} names the one new values use. Older keys stay so values they made still
 * decrypt until they are re-encrypted (rotation).
 *
 * <p>Every error names the setting, the entry's position or a key ID that passed the ID check, never anything that
 * could be key material: a malformed entry might be a key pasted in the wrong place. The decoded bytes are wiped once
 * copied into the key objects, and {@link #toString()} shows key IDs only.
 */
final class CredentialKeyring {

    static final String KEYS_SETTING = "NORTHSTAR_CREDENTIAL_KEYS";
    static final String ACTIVE_SETTING = "NORTHSTAR_CREDENTIAL_ACTIVE_KEY";
    static final int KEY_BYTES = 32;

    /** The same rule as the key_id check on secrets.integration_credentials. */
    static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private final Map<String, SecretKey> keys;
    private final String activeKeyId;

    private CredentialKeyring(Map<String, SecretKey> keys, String activeKeyId) {
        this.keys = keys;
        this.activeKeyId = activeKeyId;
    }

    static CredentialKeyring parse(String encodedKeys, String activeKeyId) {
        if (encodedKeys == null || encodedKeys.isBlank()) {
            throw new IllegalStateException(KEYS_SETTING + " is required when the worker is on");
        }
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException(ACTIVE_SETTING + " is required when the worker is on");
        }
        Map<String, SecretKey> parsed = new LinkedHashMap<>();
        String[] entries = encodedKeys.split(",", -1);
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].trim();
            int position = i + 1;
            int colon = entry.indexOf(':');
            if (colon < 0) {
                throw new IllegalStateException(KEYS_SETTING + " entry " + position + " is not keyId:base64");
            }
            String id = entry.substring(0, colon);
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalStateException(KEYS_SETTING + " entry " + position
                        + " has an invalid key ID (lowercase letters, digits and hyphens, at most 32)");
            }
            if (parsed.containsKey(id)) {
                throw new IllegalStateException(KEYS_SETTING + " names key " + id + " twice");
            }
            parsed.put(id, decodeKey(id, entry.substring(colon + 1)));
        }
        String active = activeKeyId.trim();
        if (!parsed.containsKey(active)) {
            throw new IllegalStateException(ACTIVE_SETTING + " must name a key in " + KEYS_SETTING);
        }
        return new CredentialKeyring(Map.copyOf(parsed), active);
    }

    String activeKeyId() {
        return activeKeyId;
    }

    SecretKey activeKey() {
        return keys.get(activeKeyId);
    }

    Optional<SecretKey> key(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }

    Set<String> keyIds() {
        return new TreeSet<>(keys.keySet());
    }

    @Override
    public String toString() {
        return "CredentialKeyring[active=" + activeKeyId + ", keys=" + keyIds() + "]";
    }

    /** No cause is kept: the decoder's message names the offending character of the key. */
    private static SecretKey decodeKey(String id, String encoded) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(KEYS_SETTING + " key " + id + " is not base64");
        }
        try {
            if (bytes.length != KEY_BYTES) {
                throw new IllegalStateException(KEYS_SETTING + " key " + id + " must be exactly 32 bytes");
            }
            return new SecretKeySpec(bytes, "AES");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
