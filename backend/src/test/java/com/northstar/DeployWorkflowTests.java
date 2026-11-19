package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The CI workflow's deploy rules (step 1.15, .github/workflows/ci.yml): a staging deploy never runs without the
 * STAGING_DEPLOY_ENABLED switch, a push to main, and both required checks green; the required checks themselves run on
 * every pull request and push, whatever the switch says.
 */
class DeployWorkflowTests {

    private static final Path WORKFLOW = Path.of("../.github/workflows/ci.yml");

    private static final List<String> REQUIRED_CHECKS = List.of("backend", "cross-tenant");

    /** Every condition the deploy job's if must AND together. */
    private static final List<String> DEPLOY_CONDITIONS = List.of(
            "github.event_name == 'push'", "github.ref == 'refs/heads/main'", "vars.STAGING_DEPLOY_ENABLED == 'true'");

    private final Map<String, Object> workflow = load();

    @Test
    void aDeployNeedsTheSwitchAPushToMainAndBothRequiredChecks() {
        Map<String, Object> deploy = job("deploy-staging");

        assertThat(list(deploy.get("needs"))).containsAll(REQUIRED_CHECKS);
        String condition = String.valueOf(deploy.get("if"))
                .replace("${{", "")
                .replace("}}", "")
                .trim()
                .replaceAll("\\s+", " ");
        assertThat(condition)
                .as("conditions are only ever narrowed: no OR, no negation, no status function that skips `needs`")
                .doesNotContain("||")
                .doesNotContainPattern("!(?!=)")
                .doesNotContain("always()")
                .doesNotContain("failure()")
                .doesNotContain("cancelled()");
        assertThat(Arrays.stream(condition.split("&&")).map(String::trim).toList())
                .containsAll(DEPLOY_CONDITIONS);
        assertThat(deploy).containsEntry("environment", "staging");
    }

    @Test
    void noDeployStepRunsAfterAFailedStep() {
        for (Object step : list(job("deploy-staging").get("steps"))) {
            assertThat(map(step))
                    .as(
                            "step %s: a step condition could run it after a failure",
                            map(step).get("name"))
                    .doesNotContainKey("if");
        }
    }

    @Test
    void theRequiredChecksRunOnEveryPullRequestAndPushToMain() {
        // SnakeYAML reads YAML 1.1, where the key `on` is the boolean true.
        Map<String, Object> triggers = map(workflow.containsKey("on") ? workflow.get("on") : workflow.get(true));
        assertThat(list(map(triggers.get("pull_request")).get("branches"))).containsExactly("main");
        assertThat(list(map(triggers.get("push")).get("branches"))).containsExactly("main");

        for (String check : REQUIRED_CHECKS) {
            assertThat(job(check)).as(check).doesNotContainKey("if").containsEntry("name", check);
        }
    }

    @Test
    void onlyTheDeployJobTouchesTheDeployToken() throws IOException {
        String text = Files.readString(WORKFLOW);
        int deployStart = text.indexOf("\n  deploy-staging:");
        assertThat(deployStart).isPositive();
        assertThat(text.substring(0, deployStart)).doesNotContain("secrets.");
        assertThat(text.substring(deployStart)).contains("secrets.DIGITALOCEAN_ACCESS_TOKEN");
    }

    // Helpers.

    private Map<String, Object> job(String name) {
        Map<String, Object> job = map(map(workflow.get("jobs")).get(name));
        assertThat(job).as("job " + name).isNotEmpty();
        return job;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    private static Map<String, Object> load() {
        try {
            return new Yaml().load(Files.readString(WORKFLOW));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + WORKFLOW, e);
        }
    }
}
