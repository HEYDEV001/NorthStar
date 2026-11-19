package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every database URL Northstar opens is refused if it carries a password, because logs and error messages may print
 * the URL (step 1.15). Each pool has its own copy of the check, in its own package: the app's datasource
 * (DatabaseUrlCheck), JobRunr's pool, the worker's credential pool and the migration runner (db/migrate, on this test
 * classpath). This runs the same cases against all four, so they cannot drift apart. The checks are package-private,
 * so they are reached by reflection.
 */
class UrlPasswordChecksTests {

    /** Each copy of the check, by name. */
    private static final Map<String, Method> CHECKS = checks();

    static Stream<Arguments> cases() {
        return Stream.of(
                // No credentials.
                Arguments.of("plain", "jdbc:postgresql://localhost:5432/postgres", false),
                Arguments.of("pooler with sslmode", "jdbc:postgresql://h:6543/postgres?sslmode=require", false),
                Arguments.of("user parameter", "jdbc:postgresql://h:6543/postgres?user=app_user.ref", false),
                Arguments.of("encoded user parameter", "jdbc:postgresql://h:6543/postgres?user=app%40x", false),
                Arguments.of("empty", "", false),
                // password=
                Arguments.of("password parameter", "jdbc:postgresql://h:6543/postgres?password=s3cret", true),
                Arguments.of(
                        "password after another parameter",
                        "jdbc:postgresql://h:6543/postgres?sslmode=require&password=s3cret",
                        true),
                Arguments.of("ssl key password", "jdbc:postgresql://h:6543/postgres?sslpassword=s3cret", true),
                // user:password@ (any user info before the host)
                Arguments.of("user and password", "jdbc:postgresql://u:s3cret@h:6543/postgres", true),
                Arguments.of("user only", "jdbc:postgresql://u@h:6543/postgres", true),
                // Mixed case.
                Arguments.of("mixed-case parameter", "jdbc:postgresql://h:6543/postgres?PassWord=s3cret", true),
                Arguments.of("upper-case parameter", "jdbc:postgresql://h:6543/postgres?PASSWORD=s3cret", true),
                Arguments.of("upper-case user info", "JDBC:POSTGRESQL://U:S3CRET@H:6543/POSTGRES", true),
                // Encoded.
                Arguments.of("encoded value", "jdbc:postgresql://h:6543/postgres?password=s%3Dcr%26t", true),
                Arguments.of("encoded name", "jdbc:postgresql://h:6543/postgres?pass%77ord=s3cret", true),
                Arguments.of("encoded first letter", "jdbc:postgresql://h:6543/postgres?%50assword=s3cret", true),
                Arguments.of("double-encoded name", "jdbc:postgresql://h:6543/postgres?pass%2577ord=s3cret", true),
                Arguments.of("encoded @ in the password", "jdbc:postgresql://u:s3%40cret@h:6543/postgres", true),
                Arguments.of("encoded user info", "jdbc:postgresql://u%3As3cret%40h:6543/postgres", true));
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("cases")
    void everyCopyOfTheCheckAgrees(String name, String url, boolean carriesPassword) throws Exception {
        for (Map.Entry<String, Method> check : CHECKS.entrySet()) {
            assertThat((boolean) check.getValue().invoke(null, url))
                    .as(check.getKey() + " on " + name)
                    .isEqualTo(carriesPassword);
        }
    }

    @Test
    void allFourChecksAreTested() {
        assertThat(CHECKS).hasSize(4);
    }

    private static Map<String, Method> checks() {
        Map<String, Method> checks = new LinkedHashMap<>();
        checks.put("DatabaseUrlCheck", check("com.northstar.tenancy.internal.DatabaseUrlCheck", "carriesPassword"));
        checks.put("JobsConfiguration", check("com.northstar.jobs.JobsConfiguration", "carriesPassword"));
        checks.put(
                "CredentialsConfiguration",
                check("com.northstar.integrations.internal.CredentialsConfiguration", "carriesPassword"));
        checks.put("Migrate", check("com.northstar.migrate.Migrate", "carriesCredentials"));
        return checks;
    }

    private static Method check(String type, String method) {
        try {
            Method found = Class.forName(type).getDeclaredMethod(method, String.class);
            found.setAccessible(true);
            return found;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The check " + type + "." + method + "(String) has moved", e);
        }
    }
}
