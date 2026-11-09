package com.northstar.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Request IDs on every response and log line, the log context, the filter order, and the /api/v1 conventions. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdAndConventionsTests {

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final ApplicationContext context;

    RequestIdAndConventionsTests(
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired PostgresAdmin postgres,
            @Autowired ApplicationContext context) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.context = context;
    }

    @Test
    void everyResponseCarriesARequestId() {
        String token = bearer(data.member("staff"));
        MvcTestResult ok = mvc.get().uri("/actuator/health").exchange();
        MvcTestResult unauthorized = mvc.get().uri("/api/v1/test/time").exchange();
        MvcTestResult notFound = mvc.get()
                .uri("/api/v1/test/missing")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange();

        for (MvcTestResult result : new MvcTestResult[] {ok, unauthorized, notFound}) {
            assertThat(result.getResponse().getHeader(LogContext.REQUEST_ID_HEADER))
                    .matches("[0-9a-f-]{36}");
        }
        assertThat(unauthorized)
                .bodyJson()
                .extractingPath("$.details.requestId")
                .isEqualTo(unauthorized.getResponse().getHeader(LogContext.REQUEST_ID_HEADER));
    }

    @Test
    void aSafeIncomingRequestIdIsReusedAndAnUnsafeOneReplaced() {
        assertThat(requestIdFor("client-abc-123")).isEqualTo("client-abc-123");
        for (String unsafe : new String[] {"has spaces", "semi;colon", "x".repeat(65), "line\tbreak"}) {
            assertThat(requestIdFor(unsafe)).as(unsafe).isNotEqualTo(unsafe).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void tenantAndUserJoinTheRequestIdOnLogLinesAndAreClearedAfterwards(CapturedOutput output) {
        TestData.Member member = data.member("staff");

        MvcTestResult result = mvc.get()
                .uri("/api/v1/test/log")
                .header(HttpHeaders.AUTHORIZATION, bearer(member))
                .exchange();
        String requestId = result.getResponse().getHeader(LogContext.REQUEST_ID_HEADER);

        assertThat(result).hasStatusOk();
        String context =
                "[request_id=%s tenant_id=%s user_id=%s]".formatted(requestId, member.tenantId(), member.userId());
        assertThat(output.getAll().lines())
                .anyMatch(
                        line -> line.contains(context) && line.contains("test log line from inside the tenant scope"));
        Map<String, String> leftover = MDC.getCopyOfContextMap();
        assertThat(leftover == null ? Map.of() : leftover).isEmpty();
    }

    @Test
    void filtersRunRequestIdThenErrorFilterThenSpringSecurity() {
        int requestId = order("requestIdFilter");
        int errorFilter = order("apiErrorFilter");
        int springSecurity = order("securityFilterChainRegistration");

        assertThat(requestId).isLessThan(errorFilter);
        assertThat(errorFilter).isLessThan(springSecurity);
    }

    @Test
    void routesLiveOnlyUnderApiV1() {
        String token = bearer(data.member("staff"));
        assertThat(mvc.get().uri("/api/v1/test/time").header(HttpHeaders.AUTHORIZATION, token))
                .hasStatusOk();
        assertThat(mvc.get().uri("/test/time").header(HttpHeaders.AUTHORIZATION, token))
                .hasStatus(404);
    }

    @Test
    void timestampsAreUtcIsoAndAmountsAreIntegerPaise() {
        assertThat(mvc.get().uri("/api/v1/test/time").header(HttpHeaders.AUTHORIZATION, bearer(data.member("staff"))))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        {"at": "2026-11-07T11:00:00Z", "amountPaise": 1250000}""");
    }

    private String requestIdFor(String incoming) {
        return mvc.get()
                .uri("/actuator/health")
                .header(LogContext.REQUEST_ID_HEADER, incoming)
                .exchange()
                .getResponse()
                .getHeader(LogContext.REQUEST_ID_HEADER);
    }

    private int order(String registrationBean) {
        return ((Ordered) context.getBean(registrationBean)).getOrder();
    }

    private String bearer(TestData.Member member) {
        return "Bearer " + tokens.forUser(member.userId());
    }
}
