package com.northstar.devtools;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * Reads the Meta access token for {@code gate connect} (step 2.11): from the {@value #VARIABLE} environment variable
 * when it is set, else typed at a hidden prompt when a person is at the terminal, else from standard input. Never from
 * an argument, and never echoed or repeated in an error.
 */
final class TokenInput {

    static final String VARIABLE = "NORTHSTAR_META_ACCESS_TOKEN";

    /** Meta's tokens are a few hundred characters; a longer value is a mistake. */
    static final int MAX_LENGTH = 4096;

    private TokenInput() {}

    /**
     * The token's bytes, which the caller wipes. Refused unless it is 1 to 4,096 printable characters and no spaces.
     */
    static byte[] read(Map<String, String> environment, InputStream stdin, Console console) {
        String fromEnvironment = environment.get(VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return valid(fromEnvironment.strip().getBytes(StandardCharsets.UTF_8));
        }
        if (console != null) {
            char[] typed = console.readPassword("Meta access token (not shown): ");
            if (typed == null) throw missing();
            byte[] bytes = new String(typed).strip().getBytes(StandardCharsets.UTF_8);
            Arrays.fill(typed, ' ');
            return valid(bytes);
        }
        return valid(readLine(stdin));
    }

    /** Standard input, without its line ending. */
    private static byte[] readLine(InputStream stdin) {
        byte[] read;
        try {
            read = stdin.readNBytes(MAX_LENGTH + 3); // a longer value is refused without reading the rest
        } catch (IOException unreadable) {
            throw missing();
        }
        int end = read.length;
        while (end > 0 && (read[end - 1] == '\n' || read[end - 1] == '\r')) end--;
        byte[] token = Arrays.copyOf(read, end);
        Arrays.fill(read, (byte) 0);
        return token;
    }

    private static byte[] valid(byte[] token) {
        if (token.length == 0) throw missing();
        for (byte b : token) {
            if (token.length > MAX_LENGTH || b <= 0x20 || b >= 0x7f) {
                Arrays.fill(token, (byte) 0);
                throw new LocalToolException(
                        "token_invalid",
                        "The access token must be at most " + MAX_LENGTH
                                + " printable characters with no spaces or line breaks. Nothing was done.");
            }
        }
        return token;
    }

    private static LocalToolException missing() {
        return new LocalToolException(
                "token_missing",
                "No access token: pipe it in, type it at the prompt, or set " + VARIABLE + ". Nothing was done.");
    }
}
