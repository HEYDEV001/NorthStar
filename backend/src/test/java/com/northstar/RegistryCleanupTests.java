package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The staging registry's cleanup after a deploy (step 1.15, .github/scripts): which image tags it keeps, that it never
 * deletes a running tag, and how it handles garbage collection and storage. Runs the real jq programs and the real
 * script against a fake doctl on the PATH, so nothing reaches the network.
 */
class RegistryCleanupTests {

    private static final Path SCRIPTS = Path.of("../.github/scripts").toAbsolutePath();

    /** Four tags pushed a day apart: app-a oldest, app-d newest. */
    private static final String TAGS = """
            [{"registry_name": "r", "repository": "northstar", "tag": "app-c", "updated_at": "2026-11-12T10:00:00Z"},
             {"registry_name": "r", "repository": "northstar", "tag": "app-a", "updated_at": "2026-11-10T10:00:00Z"},
             {"registry_name": "r", "repository": "northstar", "tag": "app-d", "updated_at": "2026-11-13T10:00:00Z"},
             {"registry_name": "r", "repository": "northstar", "tag": "app-b", "updated_at": "2026-11-11T10:00:00Z"}]""";

    @TempDir
    Path work;

    // Which tags are kept (registry-tags-to-delete.jq).

    @Test
    void keepsTheDeployedTagAndTheOneThatRanBefore() throws Exception {
        assertThat(plan("app-d", "[\"app-b\"]", 2))
                .isEqualTo(new Result(0, "{\"keep\":[\"app-d\",\"app-b\"],\"delete\":[\"app-a\",\"app-c\"]}"));
    }

    @Test
    void aRedeployOfTheSameTagKeepsTheNewestOtherTag() throws Exception {
        assertThat(plan("app-d", "[\"app-d\"]", 2))
                .isEqualTo(new Result(0, "{\"keep\":[\"app-d\",\"app-c\"],\"delete\":[\"app-a\",\"app-b\"]}"));
    }

    @Test
    void theFirstDeployKeepsTheNewestOtherTag() throws Exception {
        assertThat(plan("app-b", "[]", 2))
                .isEqualTo(new Result(0, "{\"keep\":[\"app-b\",\"app-d\"],\"delete\":[\"app-a\",\"app-c\"]}"));
    }

    @Test
    void aTagTheAppRanIsKeptEvenBeyondTheCount() throws Exception {
        // Components on different tags (e.g. after a manual change): every one stays, so a rollback still works.
        assertThat(plan("app-d", "[\"app-a\", \"app-b\"]", 2))
                .isEqualTo(new Result(0, "{\"keep\":[\"app-d\",\"app-a\",\"app-b\"],\"delete\":[\"app-c\"]}"));
    }

    @Test
    void onBasicItKeepsTwentyNewestFirst() throws Exception {
        assertThat(plan("app-d", "[\"app-c\"]", 20))
                .isEqualTo(new Result(0, "{\"keep\":[\"app-d\",\"app-c\",\"app-b\",\"app-a\"],\"delete\":[]}"));
    }

    @Test
    void aRunningTagNoLongerInTheRepositoryIsIgnored() throws Exception {
        assertThat(plan("app-d", "[\"app-gone\"]", 2))
                .isEqualTo(new Result(0, "{\"keep\":[\"app-d\",\"app-c\"],\"delete\":[\"app-a\",\"app-b\"]}"));
    }

    @Test
    void refusesToDeleteAnythingOnAWrongCall() throws Exception {
        assertThat(plan("app-x", "[]", 2).exitCode()).as("deployed tag missing").isNotZero();
        assertThat(plan("app-d", "[]", 1).exitCode()).as("keep below 2").isNotZero();
        String odd = TAGS.replace("\"app-a\"", "\"app-a --force\"");
        assertThat(run(
                                List.of(
                                        "jq",
                                        "-c",
                                        "--arg",
                                        "deployed",
                                        "app-d",
                                        "--argjson",
                                        "running",
                                        "[]",
                                        "--argjson",
                                        "keep",
                                        "2",
                                        "-f",
                                        SCRIPTS.resolve("registry-tags-to-delete.jq")
                                                .toString()),
                                odd,
                                Map.of())
                        .exitCode())
                .as("a tag name that could split into arguments")
                .isNotZero();
    }

    // Storage (registry-storage.jq).

    @Test
    void storageWarnsAboveEightyPercentOfThePlan() throws Exception {
        assertThat(storage(440_401_920L, 500).output())
                .contains("Registry storage: 420 MiB of 500 MiB (84%), measured 2026-11-16T10:00:00Z")
                .contains("::warning::The container registry is at 84% of its 500 MiB plan");
        assertThat(storage(400L * 1_048_576, 500).output()).contains("(80%)").doesNotContain("::warning::");
        assertThat(storage(440_401_920L, 5120).output()).contains("(8%)").doesNotContain("::warning::");
    }

    // The whole script (clean-registry.sh) against a fake doctl.

    @Test
    void theScriptDeletesTheOtherTagsStartsGarbageCollectionAndReportsStorage() throws Exception {
        Result result = cleanup(Map.of());

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(calls())
                .contains("registry repository delete-tag northstar app-a app-c --force")
                .contains("registry garbage-collection start --include-untagged-manifests --force");
        assertThat(result.output())
                .contains("Keeping: app-d, app-b")
                .contains("Deleting: app-a app-c")
                .contains("Garbage collection started")
                .contains("Registry storage: 120 MiB of 500 MiB (24%)");
    }

    @Test
    void aGarbageCollectionAlreadyRunningDoesNotFailTheDeploy() throws Exception {
        Result result = cleanup(Map.of("FAKE_GC_START", "fail", "FAKE_GC_ACTIVE", "yes"));

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("::notice::A garbage collection is already running");
    }

    @Test
    void anyOtherGarbageCollectionFailureFailsTheJob() throws Exception {
        Result result = cleanup(Map.of("FAKE_GC_START", "fail", "FAKE_GC_ACTIVE", "no"));

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("::error::Garbage collection could not start: Error: forbidden");
    }

    @Test
    void nothingIsDeletedWhenTheDeployedTagIsMissing() throws Exception {
        Result result = run(
                List.of(SCRIPTS.resolve("clean-registry.sh").toString(), "app-x", "[\"app-b\"]"),
                "",
                fakeDoctl(Map.of()));

        assertThat(result.exitCode()).isNotZero();
        assertThat(calls()).noneMatch(call -> call.contains("delete-tag") || call.contains("garbage-collection start"));
    }

    @Test
    void waitingForGarbageCollectionEndsWhenNoneIsRunningAndGivesUpOtherwise() throws Exception {
        String wait = SCRIPTS.resolve("wait-for-registry-gc.sh").toString();

        assertThat(run(List.of(wait), "", fakeDoctl(Map.of("FAKE_GC_ACTIVE", "no")))
                        .exitCode())
                .isZero();
        Result stuck = run(List.of(wait), "", fakeDoctl(Map.of("FAKE_GC_ACTIVE", "yes", "WAIT_SECONDS", "0")));
        assertThat(stuck.exitCode()).isNotZero();
        assertThat(stuck.output()).contains("::error::Registry garbage collection still running");
    }

    // Helpers.

    private record Result(int exitCode, String output) {}

    private Result plan(String deployed, String running, int keep) throws Exception {
        return run(
                List.of(
                        "jq",
                        "-c",
                        "--arg",
                        "deployed",
                        deployed,
                        "--argjson",
                        "running",
                        running,
                        "--argjson",
                        "keep",
                        String.valueOf(keep),
                        "-f",
                        SCRIPTS.resolve("registry-tags-to-delete.jq").toString()),
                TAGS,
                Map.of());
    }

    private Result storage(long bytes, int limitMib) throws Exception {
        String registry = "[{\"name\": \"r\", \"storage_usage_bytes\": " + bytes
                + ", \"storage_usage_bytes_updated_at\": \"2026-11-16T10:00:00Z\"}]";
        return run(
                List.of(
                        "jq",
                        "-r",
                        "--argjson",
                        "limit_mib",
                        String.valueOf(limitMib),
                        "-f",
                        SCRIPTS.resolve("registry-storage.jq").toString()),
                registry,
                Map.of());
    }

    private Result cleanup(Map<String, String> fake) throws Exception {
        return run(
                List.of(SCRIPTS.resolve("clean-registry.sh").toString(), "app-d", "[\"app-b\"]"), "", fakeDoctl(fake));
    }

    /**
     * A doctl stand-in first on the PATH: it records every call and answers the few the scripts make. FAKE_GC_START
     * (ok or fail) and FAKE_GC_ACTIVE (yes or no) choose how garbage collection behaves.
     */
    private Map<String, String> fakeDoctl(Map<String, String> settings) throws IOException {
        Path bin = Files.createDirectories(work.resolve("bin"));
        Files.writeString(work.resolve("tags.json"), TAGS);
        Path doctl = bin.resolve("doctl");
        Files.writeString(doctl, """
                #!/usr/bin/env bash
                echo "$*" >> "$FAKE_CALLS"
                case "$*" in
                  "registry repository list-tags northstar --output json") cat "$FAKE_TAGS" ;;
                  "registry repository delete-tag "*) ;;
                  "registry garbage-collection start "*)
                    if [ "${FAKE_GC_START:-ok}" = ok ]; then echo '{}'; else echo "Error: forbidden" >&2; exit 1; fi ;;
                  "registry garbage-collection get-active"*)
                    if [ "${FAKE_GC_ACTIVE:-no}" = yes ]; then echo '{}'; else echo "Error: 404" >&2; exit 1; fi ;;
                  "registry get --output json")
                    echo '[{"name": "r", "storage_usage_bytes": 125829120, "storage_usage_bytes_updated_at": "2026-11-16T10:00:00Z"}]' ;;
                  *) echo "unexpected doctl call: $*" >&2; exit 64 ;;
                esac
                """);
        Files.setPosixFilePermissions(doctl, PosixFilePermissions.fromString("rwx------"));
        Map<String, String> environment = new HashMap<>(settings);
        environment.put("PATH", bin + ":" + System.getenv("PATH"));
        environment.put("FAKE_CALLS", work.resolve("calls.log").toString());
        environment.put("FAKE_TAGS", work.resolve("tags.json").toString());
        return environment;
    }

    private List<String> calls() throws IOException {
        Path log = work.resolve("calls.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private static Result run(List<String> command, String input, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("finished in time").isTrue();
        return new Result(process.exitValue(), output);
    }
}
