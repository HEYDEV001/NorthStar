package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.api.LogContext;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * P9 for every endpoint that takes an ID (step 1.14), whatever its HTTP method: as tenant A's admin, another tenant's
 * record and a record that does not exist must give the same 404, body for body. Driven by Spring's registered request
 * mappings, so a new endpoint is covered without editing this test; one the test cannot exercise (no table for its ID,
 * no sample body, more than one ID) fails by name. Test-only controllers are left out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EndpointIsolationTests {

    /** Path variables whose table is not simply the variable without "Id", plus "s". None yet. */
    private static final Map<String, String> TABLE_FOR_VARIABLE = Map.of();

    /** A valid body per request body type, so validation passes and the lookup is what is tested. */
    private static final Map<String, String> SAMPLE_BODIES = Map.of("ChangeRoleRequest", "{\"role\": \"staff\"}");

    private final RequestMappingHandlerMapping mappings;
    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestData data;
    private final PostgresAdmin postgres;

    EndpointIsolationTests(
            @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
            @Autowired MockMvcTester mvc,
            @Autowired TestTokens tokens,
            @Autowired PostgresAdmin postgres) {
        this.mappings = mappings;
        this.mvc = mvc;
        this.tokens = tokens;
        this.data = new TestData(postgres);
        this.postgres = postgres;
    }

    /** One endpoint as the test exercises it. */
    private record Endpoint(String method, String pattern, String idVariable, String bodyType) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    @Test
    void P9_everyEndpointTakingAnIdAnswersTheSame404ForAnotherTenantsRecord() throws Exception {
        TenantFixtures.Side a = TenantFixtures.newSide(data);
        TenantFixtures.Side b = TenantFixtures.newSide(data);
        TenantFixtures.Seeding seeding = new TenantFixtures.Seeding(postgres.jdbc(), data);

        List<String> problems = new ArrayList<>();
        List<Endpoint> endpoints = endpointsTakingAnId(problems);
        for (Endpoint endpoint : endpoints) {
            String table = TABLE_FOR_VARIABLE.getOrDefault(endpoint.idVariable(), tableFor(endpoint.idVariable()));
            var fixture = TenantFixtures.FIXTURES.get(table);
            if (fixture == null) {
                problems.add(endpoint + ": no fixture for table " + table + " (from {" + endpoint.idVariable() + "})");
                continue;
            }
            if (endpoint.bodyType() != null && !SAMPLE_BODIES.containsKey(endpoint.bodyType())) {
                problems.add(endpoint + ": no sample body for " + endpoint.bodyType());
                continue;
            }
            UUID foreign = fixture.apply(b, seeding);
            MvcTestResult other = call(endpoint, foreign, a.adminUser());
            MvcTestResult missing = call(endpoint, UUID.randomUUID(), a.adminUser());
            int otherStatus = other.getResponse().getStatus();
            int missingStatus = missing.getResponse().getStatus();
            if (otherStatus != 404 || missingStatus != 404) {
                problems.add(endpoint + ": another tenant's record gave " + otherStatus + ", a missing one gave "
                        + missingStatus + "; both must be 404");
            } else if (!other.getResponse()
                    .getContentAsString()
                    .equals(missing.getResponse().getContentAsString())) {
                problems.add(
                        endpoint + ": the 404 for another tenant's record differs from the one for a missing record");
            }
        }

        System.out.println("P9 endpoints checked: " + endpoints);
        assertThat(endpoints).as("endpoints taking an ID").isNotEmpty();
        assertThat(problems).isEmpty();
    }

    /** Every main-code endpoint with a UUID path variable; anything the test cannot exercise is added to problems. */
    private List<Endpoint> endpointsTakingAnId(List<String> problems) {
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (isTestCode(handler.getBeanType())) continue;
            List<String> idVariables = new ArrayList<>();
            String bodyType = null;
            for (Parameter parameter : handler.getMethod().getParameters()) {
                PathVariable path = parameter.getAnnotation(PathVariable.class);
                if (path != null && parameter.getType() == UUID.class) {
                    String name = !path.value().isEmpty()
                            ? path.value()
                            : !path.name().isEmpty() ? path.name() : parameter.getName();
                    idVariables.add(name);
                }
                if (parameter.isAnnotationPresent(RequestBody.class))
                    bodyType = parameter.getType().getSimpleName();
            }
            if (idVariables.isEmpty()) continue;
            for (String pattern : entry.getKey().getPatternValues()) {
                for (RequestMethod method : methodsOf(entry.getKey())) {
                    if (idVariables.size() > 1) {
                        problems.add(method + " " + pattern + ": takes more than one ID; extend this test for it");
                        continue;
                    }
                    endpoints.add(new Endpoint(method.name(), pattern, idVariables.getFirst(), bodyType));
                }
            }
        }
        return endpoints;
    }

    private static List<RequestMethod> methodsOf(RequestMappingInfo info) {
        var methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() ? List.of(RequestMethod.GET) : List.copyOf(methods);
    }

    private MvcTestResult call(Endpoint endpoint, UUID id, UUID user) {
        String uri = endpoint.pattern().replace("{" + endpoint.idVariable() + "}", id.toString());
        var request = mvc.method(HttpMethod.valueOf(endpoint.method()))
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.forUser(user))
                .header(LogContext.REQUEST_ID_HEADER, "p9-endpoints");
        if (endpoint.bodyType() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(SAMPLE_BODIES.get(endpoint.bodyType()));
        }
        return request.exchange();
    }

    /** "membershipId" → "northstar.memberships". */
    private static String tableFor(String idVariable) {
        String entity = idVariable.endsWith("Id") ? idVariable.substring(0, idVariable.length() - 2) : idVariable;
        return "northstar." + entity.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase() + "s";
    }

    /** Controllers compiled from test sources (test-classes) are not part of the product. */
    private static boolean isTestCode(Class<?> type) {
        URL location = type.getProtectionDomain().getCodeSource().getLocation();
        return location != null && location.getPath().contains("/test-classes/");
    }
}
