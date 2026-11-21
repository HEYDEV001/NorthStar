package com.northstar.integrations.internal;

import com.northstar.workflow.ActionOutcome;
import com.northstar.workflow.FailureClass;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Meta's WhatsApp Cloud API error codes as retryable, permanent or auth (step 2.6b; integration-specs.md section 2, the
 * error table; Meta's error-code page, checked 21 Nov 2026; 80008 from the message templates reference, for the
 * management endpoints the sync calls, step 2.8a). Built on {@code error.code}, as Meta advises ("Build your
 * app's error handling around error codes instead of subcodes or HTTP response status codes"); the HTTP status decides
 * only for a code that is not listed here, or an answer without one.
 *
 * <p>Only codes Meta documents for sending, and 80008 for the management endpoints, are mapped. Anything else falls back on the status: 5xx, 408 and 429 are
 * retryable, a 401 is auth, and any other answer is permanent, keeping Meta's code in ours ({@code meta_<code>}) so it
 * can be looked up and added here. Step 2.7 uses the same table, through {@link #codeFor}, for the errors Meta reports
 * later in status webhooks.
 */
final class MetaErrors {

    /** How one of Meta's codes is handled, and the fixed code an action and its message record. */
    record Mapping(FailureClass failureClass, String code) {}

    private static final Mapping PERMISSION_MISSING = auth("permission_missing");

    private static final Map<Integer, Mapping> CODES = Map.ofEntries(
            // Authorization: the business must reconnect (decided 21 Nov 2026: revoked or removed permissions are
            // auth).
            Map.entry(0, auth("token_invalid")), // expired, invalidated, or the user changed a setting
            Map.entry(190, auth("token_expired")),
            Map.entry(3, PERMISSION_MISSING),
            Map.entry(10, PERMISSION_MISSING),
            Map.entry(131005, PERMISSION_MISSING), // 200-299 too, in forCode

            // Retryable: Meta says to try again later, or the cause is temporary.
            Map.entry(1, retryable("meta_request_or_server_error")), // "Invalid request or possible server error"
            Map.entry(2, retryable("meta_unavailable")),
            Map.entry(131016, retryable("meta_unavailable")),
            Map.entry(131000, retryable("meta_unknown_error")), // "Try again"
            Map.entry(4, retryable("rate_limited_app")),
            Map.entry(80007, retryable("rate_limited_waba")),
            // Management endpoints (the sync, step 2.8a): "Too many calls to this WhatsApp Business account".
            Map.entry(80008, retryable("rate_limited_waba_management")),
            Map.entry(130429, retryable("rate_limited_throughput")),
            Map.entry(131056, retryable("rate_limited_pair")), // "Wait and retry"
            Map.entry(131057, retryable("account_maintenance")),

            // Permanent: the request, the recipient or the template will not change by trying again.
            Map.entry(100, permanent("invalid_parameter")),
            Map.entry(131008, permanent("missing_parameter")),
            Map.entry(131009, permanent("invalid_parameter_value")),
            Map.entry(135000, permanent("invalid_request")),
            Map.entry(131051, permanent("unsupported_message_type")),
            Map.entry(131021, permanent("recipient_is_sender")),
            Map.entry(131026, permanent("recipient_unreachable")),
            Map.entry(130403, permanent("recipient_blocked_by_business")), // "Do not retry"
            Map.entry(131050, permanent("recipient_stopped_marketing")), // "Do not retry"
            Map.entry(131047, permanent("window_closed")), // Meta's own window check, past our margin
            Map.entry(131049, permanent("held_for_ecosystem")), // "Retrying does not guarantee delivery"
            Map.entry(130472, permanent("held_for_experiment")),
            Map.entry(132000, permanent("template_param_count")),
            Map.entry(132001, permanent("template_not_found")),
            Map.entry(132005, permanent("template_text_too_long")),
            Map.entry(132007, permanent("template_policy_violation")),
            Map.entry(132012, permanent("template_param_format")),
            Map.entry(132015, permanent("template_paused")),
            Map.entry(132016, permanent("template_disabled")),
            Map.entry(131063, permanent("marketing_disabled_on_cloud_api")),
            Map.entry(131042, permanent("payment_method_error")),
            Map.entry(368, permanent("account_restricted")),
            Map.entry(131031, permanent("account_restricted")),
            Map.entry(130497, permanent("country_restricted")),
            Map.entry(131048, permanent("sending_restricted")), // "Check your quality status"
            Map.entry(131064, permanent("messaging_limit_restricted")), // lifted after the enforcement period
            Map.entry(131045, permanent("number_not_registered")),
            Map.entry(133010, permanent("number_not_registered")),
            Map.entry(33, permanent("number_deleted")),
            Map.entry(131037, permanent("display_name_not_approved")));

    private MetaErrors() {}

    /** The mapping of one of Meta's codes, or empty if this table does not list it. */
    static Optional<Mapping> forCode(int metaCode) {
        if (metaCode >= 200 && metaCode <= 299) return Optional.of(PERMISSION_MISSING); // "Permission ... removed"
        return Optional.ofNullable(CODES.get(metaCode));
    }

    /** Every code this table lists, for its tests and the spec's table. */
    static Map<Integer, Mapping> listed() {
        return CODES;
    }

    /** The code of a failed status that Meta sends with no error code (step 2.7). */
    static final String DELIVERY_FAILED = "delivery_failed";

    /**
     * The fixed code for a failure Meta reports after it accepted the message, in a status webhook's
     * {@code errors[].code} (step 2.7): this table's code, {@code meta_<code>} for one it does not list, or
     * {@value #DELIVERY_FAILED} when Meta sends none. No class: a status is never retried, and its send stays succeeded.
     */
    static String codeFor(Integer metaCode) {
        if (metaCode == null || metaCode < 0) return DELIVERY_FAILED;
        return forCode(metaCode).map(Mapping::code).orElse("meta_" + metaCode);
    }

    /**
     * Classifies a failed answer: Meta's code if it sent one this table lists, else the HTTP status. retryAfter (Meta
     * documents no Retry-After header, but one is honoured if sent) is kept only on a retryable answer.
     */
    static ActionOutcome.Failed classify(int httpStatus, Integer metaCode, Duration retryAfter) {
        if (metaCode != null && metaCode >= 0) {
            Optional<Mapping> mapped = forCode(metaCode);
            if (mapped.isPresent()) return failure(mapped.get(), retryAfter);
            if (httpStatus >= 500) return failure(retryable("http_" + httpStatus), retryAfter);
            return failure(permanent("meta_" + metaCode), null);
        }
        if (httpStatus >= 500 || httpStatus == 408 || httpStatus == 429) {
            return failure(retryable("http_" + httpStatus), retryAfter);
        }
        if (httpStatus == 401) return failure(auth("http_401"), null);
        return failure(permanent("http_" + httpStatus), null);
    }

    private static ActionOutcome.Failed failure(Mapping mapping, Duration retryAfter) {
        Duration kept = mapping.failureClass() == FailureClass.RETRYABLE ? retryAfter : null;
        return new ActionOutcome.Failed(mapping.failureClass(), mapping.code(), kept);
    }

    private static Mapping auth(String code) {
        return new Mapping(FailureClass.AUTH, code);
    }

    private static Mapping retryable(String code) {
        return new Mapping(FailureClass.RETRYABLE, code);
    }

    private static Mapping permanent(String code) {
        return new Mapping(FailureClass.PERMANENT, code);
    }
}
