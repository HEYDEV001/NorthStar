package com.northstar.integrations.internal;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.FailureClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Meta's error codes as retryable, permanent or auth (step 2.6b, integration-specs.md section 2). The expected table is
 * written out here again, from Meta's error-code page (checked 21 Nov 2026), rather than read from {@link MetaErrors},
 * so a code moved to another class or renamed fails by name; and the spec's table must list exactly what the code does.
 */
class MetaErrorsTests {

    /** Meta's code → "class|our code". */
    private static final Map<Integer, String> EXPECTED = Map.ofEntries(
            entry(0, "auth|token_invalid"),
            entry(190, "auth|token_expired"),
            entry(3, "auth|permission_missing"),
            entry(10, "auth|permission_missing"),
            entry(131005, "auth|permission_missing"),
            entry(1, "retryable|meta_request_or_server_error"),
            entry(2, "retryable|meta_unavailable"),
            entry(131016, "retryable|meta_unavailable"),
            entry(131000, "retryable|meta_unknown_error"),
            entry(4, "retryable|rate_limited_app"),
            entry(80007, "retryable|rate_limited_waba"),
            entry(130429, "retryable|rate_limited_throughput"),
            entry(131056, "retryable|rate_limited_pair"),
            entry(131057, "retryable|account_maintenance"),
            entry(100, "permanent|invalid_parameter"),
            entry(131008, "permanent|missing_parameter"),
            entry(131009, "permanent|invalid_parameter_value"),
            entry(135000, "permanent|invalid_request"),
            entry(131051, "permanent|unsupported_message_type"),
            entry(131021, "permanent|recipient_is_sender"),
            entry(131026, "permanent|recipient_unreachable"),
            entry(130403, "permanent|recipient_blocked_by_business"),
            entry(131050, "permanent|recipient_stopped_marketing"),
            entry(131047, "permanent|window_closed"),
            entry(131049, "permanent|held_for_ecosystem"),
            entry(130472, "permanent|held_for_experiment"),
            entry(132000, "permanent|template_param_count"),
            entry(132001, "permanent|template_not_found"),
            entry(132005, "permanent|template_text_too_long"),
            entry(132007, "permanent|template_policy_violation"),
            entry(132012, "permanent|template_param_format"),
            entry(132015, "permanent|template_paused"),
            entry(132016, "permanent|template_disabled"),
            entry(131063, "permanent|marketing_disabled_on_cloud_api"),
            entry(131042, "permanent|payment_method_error"),
            entry(368, "permanent|account_restricted"),
            entry(131031, "permanent|account_restricted"),
            entry(130497, "permanent|country_restricted"),
            entry(131048, "permanent|sending_restricted"),
            entry(131064, "permanent|messaging_limit_restricted"),
            entry(131045, "permanent|number_not_registered"),
            entry(133010, "permanent|number_not_registered"),
            entry(33, "permanent|number_deleted"),
            entry(131037, "permanent|display_name_not_approved"));

    @Test
    void everyListedCodeHasItsClassAndCode() {
        Map<Integer, String> actual = new TreeMap<>();
        MetaErrors.listed().forEach((code, mapping) -> actual.put(code, describe(mapping)));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(EXPECTED);
        EXPECTED.forEach((code, expected) -> assertThat(MetaErrors.forCode(code))
                .as("code " + code)
                .map(MetaErrorsTests::describe)
                .contains(expected));
    }

    @Test
    void theWholePermissionRangeIsAuthAndItsNeighboursAreNotListed() {
        for (int code : List.of(200, 201, 250, 299)) {
            assertThat(MetaErrors.forCode(code))
                    .as("code " + code)
                    .map(MetaErrorsTests::describe)
                    .contains("auth|permission_missing");
        }
        assertThat(MetaErrors.forCode(199)).isEmpty();
        assertThat(MetaErrors.forCode(300)).isEmpty();
    }

    @Test
    void metasCodeDecidesBeforeTheHttpStatus() {
        assertThat(MetaErrors.classify(500, 131026, null))
                .as("a permanent code stays permanent on a 5xx")
                .isEqualTo(failed(FailureClass.PERMANENT, "recipient_unreachable", null));
        assertThat(MetaErrors.classify(400, 130429, Duration.ofSeconds(30)))
                .as("a rate limit on a 400 is retryable, after Meta's Retry-After")
                .isEqualTo(failed(FailureClass.RETRYABLE, "rate_limited_throughput", Duration.ofSeconds(30)));
        assertThat(MetaErrors.classify(400, 190, Duration.ofSeconds(30)))
                .as("a Retry-After is kept only on a retryable answer")
                .isEqualTo(failed(FailureClass.AUTH, "token_expired", null));
        assertThat(MetaErrors.classify(403, 131005, null))
                .isEqualTo(failed(FailureClass.AUTH, "permission_missing", null));
    }

    @Test
    void unlistedCodesAndAnswersWithoutOneFallBackOnTheHttpStatus() {
        assertThat(MetaErrors.classify(400, 2494100, null))
                .as("listed by Meta for phone migration, not for sending")
                .isEqualTo(failed(FailureClass.PERMANENT, "meta_2494100", null));
        assertThat(MetaErrors.classify(503, 2494100, Duration.ofSeconds(5)))
                .isEqualTo(failed(FailureClass.RETRYABLE, "http_503", Duration.ofSeconds(5)));
        assertThat(MetaErrors.classify(503, null, null)).isEqualTo(failed(FailureClass.RETRYABLE, "http_503", null));
        assertThat(MetaErrors.classify(408, null, null)).isEqualTo(failed(FailureClass.RETRYABLE, "http_408", null));
        assertThat(MetaErrors.classify(429, null, Duration.ofSeconds(7)))
                .isEqualTo(failed(FailureClass.RETRYABLE, "http_429", Duration.ofSeconds(7)));
        assertThat(MetaErrors.classify(401, null, Duration.ofSeconds(7)))
                .isEqualTo(failed(FailureClass.AUTH, "http_401", null));
        assertThat(MetaErrors.classify(403, null, null)).isEqualTo(failed(FailureClass.PERMANENT, "http_403", null));
        assertThat(MetaErrors.classify(404, null, null)).isEqualTo(failed(FailureClass.PERMANENT, "http_404", null));
        assertThat(MetaErrors.classify(302, null, null))
                .as("a redirect is never followed")
                .isEqualTo(failed(FailureClass.PERMANENT, "http_302", null));
        assertThat(MetaErrors.classify(400, -1, null))
                .as("a negative code is no code")
                .isEqualTo(failed(FailureClass.PERMANENT, "http_400", null));
    }

    @Test
    void aFailureReportedInAStatusTakesTheTablesCodeWithoutAClass() {
        assertThat(MetaErrors.codeFor(131026)).isEqualTo("recipient_unreachable");
        assertThat(MetaErrors.codeFor(130429))
                .as("a code the send would retry is only a code here: a status is never retried")
                .isEqualTo("rate_limited_throughput");
        assertThat(MetaErrors.codeFor(250)).isEqualTo("permission_missing");
        assertThat(MetaErrors.codeFor(999999)).isEqualTo("meta_999999");
        assertThat(MetaErrors.codeFor(null)).isEqualTo("delivery_failed");
        assertThat(MetaErrors.codeFor(-1)).isEqualTo("delivery_failed");
    }

    @Test
    void theSpecsTableListsExactlyWhatTheCodeDoes() throws IOException {
        String spec = Files.readString(Path.of("../docs/integration-specs.md"));
        List<String> rows = spec.lines()
                .filter(line -> line.matches("\\| [0-9–]+ \\| (auth|retryable|permanent) \\| `[a-z0-9_]+` \\|.*"))
                .toList();

        EXPECTED.forEach((code, expected) -> {
            String[] parts = expected.split("\\|");
            assertThat(rows)
                    .as("integration-specs.md section 2 lists code " + code)
                    .anyMatch(row -> row.startsWith("| " + code + " | " + parts[0] + " | `" + parts[1] + "` |"));
        });
        assertThat(rows).anyMatch(row -> row.startsWith("| 200–299 | auth | `permission_missing` |"));
        assertThat(rows)
                .as("one row per listed code, plus the permission range")
                .hasSize(EXPECTED.size() + 1);
    }

    private static String describe(MetaErrors.Mapping mapping) {
        return mapping.failureClass().name().toLowerCase(Locale.ROOT) + "|" + mapping.code();
    }

    private static ActionOutcome.Failed failed(FailureClass failureClass, String code, Duration retryAfter) {
        return new ActionOutcome.Failed(failureClass, code, retryAfter);
    }
}
