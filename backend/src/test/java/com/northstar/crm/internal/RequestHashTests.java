package com.northstar.crm.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The request hash stored with an Idempotency-Key (P21): step 2.10's encoding, kept exactly when step 3.3b shared it,
 * so a retry across the change still matches its key.
 */
class RequestHashTests {

    @Test
    void manualEntrysHashIsStillStep210sEncoding() throws Exception {
        UUID assignee = UUID.fromString("7b0c6a52-3f1e-4c3a-9d1b-2a4f5e6d7c8b");
        String canonical = "lead.create:v1|13:+919876543210|4:Asha|-|36:" + assignee;
        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(ManualLeadEntry.requestHash("+919876543210", "Asha", null, assignee))
                .isEqualTo(expected);
    }

    @Test
    void fieldsAreLengthPrefixedSoNoTwoRequestsShareAnEncoding() {
        assertThat(RequestHash.of("op", "ab", "c")).isNotEqualTo(RequestHash.of("op", "a", "bc"));
        assertThat(RequestHash.of("op", "a", null)).isNotEqualTo(RequestHash.of("op", "a", "-"));
        assertThat(RequestHash.of("op", "a")).isNotEqualTo(RequestHash.of("other", "a"));
        assertThat(RequestHash.of("op", "a")).matches("[0-9a-f]{64}");
    }
}
