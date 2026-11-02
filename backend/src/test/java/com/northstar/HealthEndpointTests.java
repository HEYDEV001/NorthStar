package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthEndpointTests {

    private final MockMvcTester mvc;

    HealthEndpointTests(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void healthIsUpWithDatabaseConnected() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void healthHidesComponentDetails() {
        assertThat(mvc.get().uri("/actuator/health")).bodyJson().doesNotHavePath("$.components");
    }

    @Test
    void onlyHealthIsExposed() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(404);
    }
}
