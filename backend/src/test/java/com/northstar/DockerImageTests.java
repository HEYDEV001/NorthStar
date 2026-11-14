package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The backend image's build files (step 1.15): the build context is an allowlist that never lets an env file in, base
 * images are pinned by digest, and the app runs as a non-root user.
 */
class DockerImageTests {

    private static final Path DOCKERIGNORE = Path.of("../.dockerignore");
    private static final Path DOCKERFILE = Path.of("Dockerfile");

    @Test
    void theBuildContextIsAnAllowlistThatNeverIncludesAnEnvFile() throws IOException {
        List<String> rules = rules(DOCKERIGNORE);

        assertThat(rules.getFirst()).as("everything is excluded first").isEqualTo("*");
        assertThat(rules.getLast())
                .as("env files are excluded last, after every allowed path")
                .isEqualTo("**/.env*");
        assertThat(rules.subList(1, rules.size() - 1))
                .allSatisfy(rule -> assertThat(rule).startsWith("!"))
                .noneSatisfy(rule -> assertThat(rule).containsAnyOf(".env", "supabase", ".claude", "**", "test"));
    }

    @Test
    void baseImagesArePinnedAndTheAppRunsAsANonRootUser() throws IOException {
        String dockerfile = Files.readString(DOCKERFILE);
        List<String> images = dockerfile
                .lines()
                .filter(line -> line.startsWith("ARG ") && line.contains("_IMAGE="))
                .toList();

        assertThat(images).hasSize(2).allSatisfy(image -> assertThat(image).containsPattern("@sha256:[0-9a-f]{64}$"));
        assertThat(dockerfile.lines().filter(line -> line.startsWith("FROM ")))
                .allSatisfy(from -> assertThat(from).startsWith("FROM ${"));
        assertThat(dockerfile).containsPattern("(?m)^USER 10001:10001$");
        assertThat(dockerfile).doesNotContain("ENTRYPOINT").contains("CMD [\"/opt/northstar/bin/start-app\"]");
    }

    private static List<String> rules(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }
}
