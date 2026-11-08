package com.northstar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import com.northstar.tenancy.internal.MembershipLookup;
import jakarta.servlet.RequestDispatcher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Failures inside the filters (here the membership lookup in the security chain) come back in the {code, message,
 * details} format with the request ID, never as a stack trace or an HTML page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class FilterErrorTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final ApiErrorMapper errors;
    private final ApiErrorWriter writer;

    @MockitoBean
    MembershipLookup lookup; // fails on demand

    FilterErrorTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired ApiErrorMapper errors,
            @Autowired ApiErrorWriter writer) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.errors = errors;
        this.writer = writer;
    }

    @Test
    void databaseDownDuringTheMembershipLookupIs503() throws Exception {
        given(lookup.activeMembershipOf(any()))
                .willThrow(new DataAccessResourceFailureException("db down at 10.0.0.5:5432"));

        MvcTestResult result = request();
        String requestId = result.getResponse().getHeader(LogContext.REQUEST_ID_HEADER);

        assertThat(result)
                .hasStatus(503)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "service_unavailable", "message": "The service is temporarily unavailable",
                         "details": {"requestId": "%s"}}""".formatted(requestId));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("10.0.0.5", "db down", "Exception");
    }

    @Test
    void anyOtherFilterFailureIs500AndLogged(CapturedOutput output) throws Exception {
        given(lookup.activeMembershipOf(any())).willThrow(new IllegalStateException("filter secret"));

        MvcTestResult result = request();
        String requestId = result.getResponse().getHeader(LogContext.REQUEST_ID_HEADER);

        assertThat(result)
                .hasStatus(500)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("internal_error");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("filter secret", "<html");
        assertThat(output.getAll()).contains("request_id=" + requestId).contains("filter secret");
    }

    /** Anything the container still sends to /error answers in our format, as JSON, whatever was asked for. */
    @Test
    void errorFallbackAnswersInTheApiFormat() throws Exception {
        ApiErrorController fallback = new ApiErrorController(errors, writer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.addHeader(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        MockHttpServletResponse response = new MockHttpServletResponse();

        fallback.error(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"not_found\"");
    }

    private MvcTestResult request() {
        return mvc.get()
                .uri("/api/v1/test/missing")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(UUID.randomUUID()))
                .exchange();
    }
}
