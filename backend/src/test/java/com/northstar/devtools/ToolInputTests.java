package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How a gate tool (step 2.11) reads the access token and its arguments: the token is never an argument, never shown.
 */
class ToolInputTests {

    private static final String TOKEN = "EAAGtest0123456789";

    @Test
    void theTokenComesFromTheEnvironmentVariableBeforeStandardInput() {
        assertThat(read(Map.of(TokenInput.VARIABLE, " " + TOKEN + "\n"), "something-else"))
                .isEqualTo(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {TOKEN, TOKEN + "\n", TOKEN + "\r\n"})
    void theTokenIsReadFromStandardInputWithoutItsLineEnding(String piped) {
        assertThat(read(Map.of(), piped)).isEqualTo(TOKEN);
        assertThat(read(Map.of(TokenInput.VARIABLE, "  "), piped))
                .as("a blank variable counts as unset")
                .isEqualTo(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "\r\n"})
    void noTokenIsRefused(String piped) {
        assertThatThrownBy(() -> read(Map.of(), piped))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("token_missing"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"two words", "two\nlines", "tab\there", "café", "nul\u0000byte", "\u007f"})
    void aTokenWithAnythingButPrintableCharactersIsRefusedWithoutShowingIt(String piped) {
        assertThatThrownBy(() -> read(Map.of(), piped)).isInstanceOfSatisfying(LocalToolException.class, e -> {
            assertThat(e.code()).isEqualTo("token_invalid");
            assertThat(e.getMessage()).doesNotContain(piped, "two", "caf");
        });
    }

    @Test
    void aTokenLongerThanMetasIsRefused() {
        assertThat(read(Map.of(), "x".repeat(TokenInput.MAX_LENGTH))).hasSize(TokenInput.MAX_LENGTH);
        assertThatThrownBy(() -> read(Map.of(), "x".repeat(TokenInput.MAX_LENGTH + 1)))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("token_invalid"));
        assertThatThrownBy(() -> read(Map.of(), "x".repeat(1_000_000)))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("token_invalid"));
    }

    @Test
    void unreadableStandardInputIsRefused() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed");
            }
        };
        assertThatThrownBy(() -> TokenInput.read(Map.of(), broken, null))
                .isInstanceOfSatisfying(
                        LocalToolException.class, e -> assertThat(e.code()).isEqualTo("token_missing"));
    }

    @Test
    void argumentsAreNamedOnceAndKnown() {
        ToolArguments arguments =
                ToolArguments.parse(List.of("--tenant=abc", "--text=a=b, c", "--from="), "tenant", "text", "from");

        assertThat(arguments.require("tenant")).isEqualTo("abc");
        assertThat(arguments.get("text")).as("only the first = separates").contains("a=b, c");
        assertThat(arguments.get("from")).as("empty counts as not given").isEmpty();
        assertThat(arguments.integer("missing", 7)).isEqualTo(7);
    }

    @Test
    void aWrongArgumentIsRefusedWithoutRepeatingItsValue() {
        for (List<String> wrong : List.of(
                List.of("--token=hush-hush"),
                List.of("--access-token=hush-hush"),
                List.of("--unknown=hush-hush"),
                List.of("--tenant=hush-hush", "--tenant=hush-hush"),
                List.of("hush-hush"),
                List.of("-tenant=hush-hush"),
                List.of("--=hush-hush"),
                List.of("--tenant"))) {
            assertThatThrownBy(() -> ToolArguments.parse(wrong, "tenant"))
                    .as(wrong.getFirst().replace("hush-hush", "..."))
                    .isInstanceOfSatisfying(LocalToolException.class, e -> {
                        assertThat(e.code()).isEqualTo("argument_invalid");
                        assertThat(e.getMessage()).doesNotContain("hush");
                    });
        }
        ToolArguments arguments = ToolArguments.parse(List.of("--tenant=hush-hush"), "tenant", "n");
        assertThatThrownBy(() -> arguments.requireUuid("tenant"))
                .isInstanceOf(LocalToolException.class)
                .hasMessageNotContaining("hush");
        assertThatThrownBy(() -> arguments.require("n"))
                .isInstanceOf(LocalToolException.class)
                .hasMessageContaining("--n");
        assertThatThrownBy(() -> arguments.integer("tenant", 0))
                .isInstanceOf(LocalToolException.class)
                .hasMessageNotContaining("hush");
    }

    private static String read(Map<String, String> environment, String piped) {
        byte[] token =
                TokenInput.read(environment, new ByteArrayInputStream(piped.getBytes(StandardCharsets.UTF_8)), null);
        return new String(token, StandardCharsets.UTF_8);
    }
}
