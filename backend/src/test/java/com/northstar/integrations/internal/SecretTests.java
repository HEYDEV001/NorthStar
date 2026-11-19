package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The plaintext holder (step 2.1b): it prints as redacted however it is printed, compares by identity only, wipes its
 * bytes on close and refuses use afterwards. The renderings below are the mutation check for toString: if it ever
 * returned the value, in any encoding, one of them would show it.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretTests {

    private static final String PLAINTEXT = "EAAB-plaintext-marker-7f3c9d";

    /** A record that holds a secret, as an adapter's request object might. */
    private record Request(String endpoint, Secret token) {}

    @Test
    void everyWayOfPrintingASecretShowsOnlyRedacted(CapturedOutput output) {
        Secret secret = Secret.ofUtf8(PLAINTEXT);

        List<String> renderings = List.of(
                secret.toString(),
                String.valueOf(secret),
                "token=" + secret,
                String.format("token=%s", secret),
                new Request("/messages", secret).toString(),
                List.of(secret).toString(),
                Map.of("token", secret).toString(),
                Optional.of(secret).toString(),
                new IllegalStateException("failed with " + secret).getMessage());
        LoggerFactory.getLogger(SecretTests.class).info("sending with token {}", secret);

        assertThat(secret.toString()).isEqualTo("Secret[redacted]");
        for (String rendering : renderings) {
            assertThat(rendering).contains(Secret.REDACTED);
            assertNoTraceOfThePlaintext(rendering);
        }
        assertThat(output.getAll()).contains("sending with token Secret[redacted]");
        assertNoTraceOfThePlaintext(output.getAll());
    }

    @Test
    void equalityIsIdentityAndItCannotBeSerialized() throws NoSuchMethodException {
        Secret one = Secret.ofUtf8(PLAINTEXT);
        Secret same = Secret.ofUtf8(PLAINTEXT);

        assertThat(one).isNotEqualTo(same).isEqualTo(one);
        assertThat(Secret.class.getMethod("equals", Object.class).getDeclaringClass())
                .as("no content comparison, which could be timed")
                .isEqualTo(Object.class);
        assertThat(Secret.class.getMethod("hashCode").getDeclaringClass()).isEqualTo(Object.class);
        assertThat(Serializable.class.isAssignableFrom(Secret.class)).isFalse();
        assertThat(Secret.class.getMethod("toString").getDeclaringClass()).isEqualTo(Secret.class);
    }

    @Test
    void theValueIsReadOnlyThroughUseOrUtf8() {
        try (Secret secret = Secret.ofUtf8(PLAINTEXT)) {
            assertThat(secret.utf8()).isEqualTo(PLAINTEXT);
            String throughUse = secret.use(bytes -> new String(bytes, StandardCharsets.UTF_8));
            assertThat(throughUse).isEqualTo(PLAINTEXT);
        }
    }

    @Test
    void closingWipesTheBytesAndRefusesFurtherUse() {
        Secret secret = Secret.ofUtf8(PLAINTEXT);
        AtomicReference<byte[]> inside = new AtomicReference<>();
        secret.use(bytes -> {
            inside.set(bytes); // only a test keeps the array, to see it wiped
            return null;
        });

        secret.close();

        assertThat(inside.get()).hasSize(PLAINTEXT.length()).containsOnly((byte) 0);
        assertThatThrownBy(secret::utf8)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The secret has been closed");
        assertThatThrownBy(() -> secret.use(bytes -> bytes.length)).isInstanceOf(IllegalStateException.class);
        secret.close(); // closing twice is harmless
    }

    @Test
    void wrapTakesOwnershipAndRefusesAnEmptyValue() {
        byte[] bytes = PLAINTEXT.getBytes(StandardCharsets.UTF_8);
        Secret secret = Secret.wrap(bytes);
        secret.close();

        assertThat(bytes).as("the wrapped array is the one wiped").containsOnly((byte) 0);
        assertThatThrownBy(() -> Secret.wrap(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Secret.ofUtf8("")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertNoTraceOfThePlaintext(String text) {
        assertNoTraceOf(PLAINTEXT, text);
    }

    /**
     * The plaintext in any of the forms a careless toString might use: text, hex, base64, or the array's contents. Test
     * plaintexts contain "marker", so a fragment of one is caught too.
     */
    static void assertNoTraceOf(String plaintext, String text) {
        assertThat(plaintext).contains("marker");
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        assertThat(text)
                .doesNotContain(plaintext)
                .doesNotContain("marker")
                .doesNotContainIgnoringCase(HexFormat.of().formatHex(bytes))
                .doesNotContain(Base64.getEncoder().encodeToString(bytes))
                .doesNotContain(java.util.Arrays.toString(bytes));
    }
}
