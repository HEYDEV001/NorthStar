package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A request that reaches the database without a tenant context gets a 500 and an error log, never a quiet result. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MissingTenantContextHandlingTests.ForgetfulEndpoint.class})
@ExtendWith(OutputCaptureExtension.class)
class MissingTenantContextHandlingTests {

    private final MockMvcTester mvc;

    MissingTenantContextHandlingTests(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void P10_requestWithoutTenantContextFailsWith500AndErrorLog(CapturedOutput output) {
        assertThat(mvc.get().uri("/test/forgot-tenant-context"))
                .hasStatus(500)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "internal_error", "message": "Internal error", "details": {}}""");
        assertThat(output.getOut()).contains("ERROR").contains("Database access without tenant context");
    }

    /** A deliberately buggy endpoint: it reads tenant data without opening a tenant scope. */
    @RestController
    static class ForgetfulEndpoint {

        private final MembershipRepository memberships;

        ForgetfulEndpoint(MembershipRepository memberships) {
            this.memberships = memberships;
        }

        @GetMapping("/test/forgot-tenant-context")
        int countMemberships() {
            return memberships.findAll().size();
        }
    }
}
