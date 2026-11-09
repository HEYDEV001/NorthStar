package com.northstar.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestTokens;
import com.northstar.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** P9: another tenant's record is reported exactly like a missing one, so its existence is never revealed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TenantRecordIsolationTests {

    private static final String SAME_REQUEST_ID = "p9-check";

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;

    TenantRecordIsolationTests(
            @Autowired MockMvcTester mvc, @Autowired TestTokens tokens, @Autowired PostgresAdmin postgres) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
    }

    @Test
    void P9_otherTenantsRecordIsIndistinguishableFromAMissingOne() throws Exception {
        TestData.Member memberOfA = data.member("admin");
        TestData.Member memberOfB = data.member("admin");
        String token = tokens.forUser(memberOfA.userId());
        UUID othersRecord = memberOfB.membershipId();
        UUID missingRecord = UUID.randomUUID();

        for (String path : new String[] {"/api/v1/test/memberships/%s", "/api/v1/test/memberships/%s/native"}) {
            MvcTestResult other = mvc.get()
                    .uri(path.formatted(othersRecord))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(LogContext.REQUEST_ID_HEADER, SAME_REQUEST_ID)
                    .exchange();
            MvcTestResult missing = mvc.get()
                    .uri(path.formatted(missingRecord))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(LogContext.REQUEST_ID_HEADER, SAME_REQUEST_ID)
                    .exchange();

            assertThat(other).as(path).hasStatus(404);
            assertThat(other.getResponse().getContentAsString())
                    .as(path)
                    .isEqualTo(missing.getResponse().getContentAsString());
        }

        MvcTestResult otherWrite = mvc.patch()
                .uri("/api/v1/test/memberships/%s/native".formatted(othersRecord))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(LogContext.REQUEST_ID_HEADER, SAME_REQUEST_ID)
                .exchange();
        MvcTestResult missingWrite = mvc.patch()
                .uri("/api/v1/test/memberships/%s/native".formatted(missingRecord))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(LogContext.REQUEST_ID_HEADER, SAME_REQUEST_ID)
                .exchange();
        assertThat(otherWrite).hasStatus(404);
        assertThat(otherWrite.getResponse().getContentAsString())
                .isEqualTo(missingWrite.getResponse().getContentAsString());
    }

    @Test
    void ownRecordIsFoundByEveryPath() {
        TestData.Member member = data.member("admin");
        String token = "Bearer " + tokens.forUser(member.userId());

        assertThat(mvc.get()
                        .uri("/api/v1/test/memberships/%s".formatted(member.membershipId()))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .hasStatusOk();
        assertThat(mvc.get()
                        .uri("/api/v1/test/memberships/%s/native".formatted(member.membershipId()))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .hasStatusOk();
        assertThat(mvc.patch()
                        .uri("/api/v1/test/memberships/%s/native".formatted(member.membershipId()))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .hasStatusOk();
    }
}
