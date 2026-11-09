package com.northstar.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Every error the API returns uses {code, message, details} and never exposes internals (step 1.6). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorHandlingTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;

    ApiErrorHandlingTests(
            @Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
    }

    @Test
    void validationErrorIs400WithFieldDetailsButNotTheValue() {
        MvcTestResult result = post("/api/v1/test/payments", """
                {"name": "  ", "amountPaise": -5}""");

        assertThat(result).hasStatus(400).bodyJson().isLenientlyEqualTo("""
                        {"code": "validation_failed", "message": "The request is not valid",
                         "details": {"fields": [
                           {"field": "amountPaise", "rule": "PositiveOrZero"},
                           {"field": "name", "rule": "NotBlank"}]}}""");
        // The random request ID can itself contain "-5", so leave it out before checking the value is not echoed.
        String requestId = result.getResponse().getHeader(LogContext.REQUEST_ID_HEADER);
        assertThat(body(result).replace(requestId, "")).doesNotContain("-5");
    }

    @Test
    void invalidRequestParameterIs400WithFieldDetails() {
        assertThat(get("/api/v1/test/count?count=0")).hasStatus(400).bodyJson().isLenientlyEqualTo("""
                        {"code": "validation_failed", "details": {"fields": [{"field": "count", "rule": "Min"}]}}""");
    }

    @Test
    void unreadableRequestsAre400MalformedWithoutParserDetails() {
        for (String body : new String[] {
            "{\"name\": \"Shoot\", \"amountPaise\": 100.5}", // a fraction of a paisa is never truncated
            "{\"name\": \"Shoot\", \"amountPaise\": 100, \"tenantId\": \"x\"}", // unknown field
            "{\"name\": \"Shoot\", \"amountPaise\": null}", // null into a primitive
            "{\"name\": \"Shoot\", \"amountPaise\": 100} trailing", // trailing tokens
            "{not json"
        }) {
            MvcTestResult result = post("/api/v1/test/payments", body);
            assertThat(result)
                    .as(body)
                    .hasStatus(400)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("malformed_request");
            assertThat(body(result)).doesNotContain("Jackson", "Exception", "line:", "tenantId");
        }
        assertThat(get("/api/v1/test/count?count=abc"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed_request");
    }

    @Test
    void unknownRouteIs404AndWrongMethodIs405() {
        assertThat(get("/api/v1/no-such-route"))
                .hasStatus(404)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
        assertThat(mvc.delete().uri("/api/v1/test/missing").header(HttpHeaders.AUTHORIZATION, bearer()))
                .hasStatus(405)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("method_not_allowed");
    }

    @Test
    void nonJsonBodyIs415() {
        assertThat(mvc.post()
                        .uri("/api/v1/test/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=Shoot"))
                .hasStatus(415)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("unsupported_media_type");
    }

    @Test
    void notFoundExceptionIs404() {
        assertThat(get("/api/v1/test/missing")).hasStatus(404).bodyJson().isLenientlyEqualTo("""
                        {"code": "not_found", "message": "Not found"}""");
    }

    @Test
    void P9_aByIdMissIsLoggedWithItsRecordTypeAndIdOnly(CapturedOutput output) {
        java.util.UUID requested = java.util.UUID.randomUUID();

        assertThat(get("/api/v1/test/memberships/" + requested)).hasStatus(404);
        assertThat(get("/api/v1/test/memberships/evil-xyz-not-a-uuid"))
                .hasStatus(400)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("malformed_request");

        assertThat(output.getAll().lines())
                .anyMatch(line -> line.contains(" INFO ") && line.endsWith("Not found: membership " + requested));
        assertThat(output.getAll()).doesNotContain("evil-xyz-not-a-uuid");
    }

    @Test
    void serviceConflictIs409WithItsOwnCode() {
        assertThat(get("/api/v1/test/slug-taken")).hasStatus(409).bodyJson().isLenientlyEqualTo("""
                        {"code": "slug_taken", "message": "That slug is already taken"}""");
    }

    @Test
    void optimisticLockFailureIs409StaleVersion() {
        MvcTestResult result = get("/api/v1/test/stale");
        assertThat(result).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("stale_version");
        assertThat(body(result)).doesNotContain("secret-row", "leads");
    }

    @Test
    void databaseUniqueViolationIs409WithoutConstraintColumnOrValue() {
        MvcTestResult result = asAdmin(mvc.post().uri("/api/v1/test/duplicate-membership"));

        assertThat(result).hasStatus(409).bodyJson().isLenientlyEqualTo("""
                        {"code": "conflict", "message": "This conflicts with an existing record"}""");
        assertThat(body(result)).doesNotContain("memberships", "user_id", "tenant_id", "_key", "23505");
    }

    @Test
    void foreignKeyViolationIs400InvalidReference() {
        MvcTestResult result = asAdmin(mvc.post().uri("/api/v1/test/dangling-skill"));

        assertThat(result).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("invalid_reference");
        assertThat(body(result)).doesNotContain("staff_skills", "membership_id", "fkey");
    }

    @Test
    void checkViolationIs400ValidationFailed() {
        MvcTestResult result = asAdmin(mvc.post().uri("/api/v1/test/bad-role"));

        assertThat(result).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("validation_failed");
        assertThat(body(result)).doesNotContain("owner", "role_check", "memberships");
    }

    @Test
    void unexpectedErrorIs500WithoutInternalsAndIsLoggedWithTheRequestId(CapturedOutput output) {
        MvcTestResult result = get("/api/v1/test/boom");
        String requestId = result.getResponse().getHeader(LogContext.REQUEST_ID_HEADER);

        assertThat(result).hasStatus(500).bodyJson().isLenientlyEqualTo("""
                        {"code": "internal_error", "message": "Internal error", "details": {"requestId": "%s"}}""".formatted(requestId));
        assertThat(body(result)).doesNotContain("secret", "4111", "IllegalStateException", "at com.");
        assertThat(output.getAll())
                .contains("request_id=" + requestId)
                .contains("Unexpected failure on GET /api/v1/test/boom")
                .contains("IllegalStateException");
    }

    @Test
    void lostTenantContextIs500AndLogged(CapturedOutput output) {
        MvcTestResult result = get("/api/v1/test/lost-context");

        assertThat(result).hasStatus(500).bodyJson().extractingPath("$.code").isEqualTo("internal_error");
        assertThat(output.getAll()).contains("ERROR").contains("MissingTenantContextException");
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();
    }

    private MvcTestResult post(String uri, String json) {
        return mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult asAdmin(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer()).exchange();
    }

    private String bearer() {
        return "Bearer " + tokens.forUser(data.member("admin").userId());
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
