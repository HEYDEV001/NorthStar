package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The staging app spec (.do/staging.app.yaml, step 1.15): which component gets which variable, that secrets never have
 * a value in the repo, and the health check. The deploy job checks the live app against this file.
 */
class StagingAppSpecTests {

    private static final Path SPEC = Path.of("../.do/staging.app.yaml");

    private static final Set<String> APP_VARIABLES =
            Set.of("DB_URL", "DB_USER", "DB_PASSWORD", "JOBRUNR_DB_USER", "JOBRUNR_DB_PASSWORD", "SUPABASE_URL");

    /** What each component reads, exactly: nothing more reaches it. */
    private static final Map<String, Set<String>> VARIABLES = Map.of(
            "api",
            APP_VARIABLES,
            "worker",
            union(
                    APP_VARIABLES,
                    Set.of(
                            "NORTHSTAR_WORKER_ENABLED",
                            "NORTHSTAR_EMAIL_PROVIDER",
                            "NORTHSTAR_EMAIL_FROM",
                            "RESEND_API_KEY",
                            "NORTHSTAR_INVITE_TOKEN_KEY",
                            "NORTHSTAR_APP_URL")),
            "migrate",
            Set.of("FLYWAY_URL", "FLYWAY_USER", "FLYWAY_PASSWORD"));

    private static final Set<String> SECRETS = Set.of(
            "DB_PASSWORD", "JOBRUNR_DB_PASSWORD", "FLYWAY_PASSWORD", "RESEND_API_KEY", "NORTHSTAR_INVITE_TOKEN_KEY");

    private final Map<String, Object> spec = load();

    @Test
    void eachComponentGetsExactlyTheVariablesItReads() {
        assertThat(spec).doesNotContainKey("envs").as("no app-wide variables: they would reach every component");
        assertThat(components().keySet()).containsExactlyInAnyOrder("api", "worker", "migrate");
        components()
                .forEach((name, component) -> assertThat(variables(component).keySet())
                        .as(name)
                        .containsExactlyInAnyOrderElementsOf(VARIABLES.get(name)));
    }

    @Test
    void secretsAreEncryptedRuntimeVariablesWithNoValueInTheRepo() {
        List<String> problems = new ArrayList<>();
        components()
                .forEach((name, component) -> variables(component).forEach((key, variable) -> {
                    if (!"RUN_TIME".equals(variable.get("scope"))) problems.add(name + "." + key + " is not RUN_TIME");
                    boolean secret = "SECRET".equals(variable.get("type"));
                    if (SECRETS.contains(key) != secret)
                        problems.add(name + "." + key + " has type " + variable.get("type"));
                    if (secret && variable.containsKey("value"))
                        problems.add(name + "." + key + " has a value in the repo");
                    if (key.matches(".*(SERVICE_ROLE|SECRET_KEY|JWT_SECRET|ANON|SUPABASE_KEY).*")) {
                        problems.add(name + "." + key + ": the backend never uses Supabase's secret or service keys");
                    }
                }));
        assertThat(problems).isEmpty();
    }

    @Test
    void onlyTheWorkerRunsJobs() {
        components().forEach((name, component) -> {
            Map<String, Object> enabled = variables(component).get("NORTHSTAR_WORKER_ENABLED");
            if (name.equals("worker")) {
                assertThat(enabled).containsEntry("value", "true");
            } else {
                assertThat(enabled).as(name).isNull();
            }
        });
    }

    @Test
    void theApiIsCheckedOnHealthOnlyAndMigrationsRunBeforeEachDeploy() {
        Map<String, Object> api = components().get("api");
        Map<String, Object> migrate = components().get("migrate");

        assertThat(map(api.get("health_check"))).containsEntry("http_path", "/actuator/health");
        assertThat(migrate)
                .containsEntry("kind", "PRE_DEPLOY")
                .containsEntry("run_command", "/opt/northstar/bin/migrate");
        assertThat(api).containsEntry("run_command", "/opt/northstar/bin/start-app");
        assertThat(components().get("worker")).containsEntry("run_command", "/opt/northstar/bin/start-app");
        assertThat(spec).containsEntry("region", "blr");
        components().forEach((name, component) -> {
            Map<String, Object> image = map(component.get("image"));
            assertThat(image).as(name).containsEntry("registry_type", "DOCR").containsEntry("repository", "northstar");
            assertThat(map(image.get("deploy_on_push")))
                    .as(name + ": deploys come only from CI")
                    .containsEntry("enabled", false);
            String command = (String) component.get("run_command");
            assertThat(Path.of("docker", Path.of(command).getFileName().toString()))
                    .as(name + " runs a script the image ships")
                    .exists();
        });
    }

    // Helpers.

    private Map<String, Map<String, Object>> components() {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        for (String kind : List.of("services", "workers", "jobs")) {
            for (Object component : list(spec.get(kind))) {
                Map<String, Object> fields = map(component);
                found.put((String) fields.get("name"), fields);
            }
        }
        return found;
    }

    private static Map<String, Map<String, Object>> variables(Map<String, Object> component) {
        Map<String, Map<String, Object>> found = new TreeMap<>();
        for (Object variable : list(component.get("envs"))) {
            Map<String, Object> fields = map(variable);
            found.put((String) fields.get("key"), fields);
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        return value == null ? List.of() : (List<?>) value;
    }

    private static Map<String, Object> load() {
        try {
            return new Yaml().load(Files.readString(SPEC));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SPEC, e);
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> all = new TreeSet<>(first);
        all.addAll(second);
        return all;
    }
}
