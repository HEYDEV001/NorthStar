package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * db/bootstrap/verify.sql, which an operator runs on each hosted environment (step 1.15): on a correctly bootstrapped
 * and migrated database every line says PASS, and a role given the wrong row-level-security setting is a FAIL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BootstrapVerifyTests {

    private static final Path VERIFY = Path.of("../db/bootstrap/verify.sql");

    private final PostgresAdmin postgres;

    BootstrapVerifyTests(@Autowired PostgresAdmin postgres) {
        this.postgres = postgres;
    }

    /** One line of verify.sql's output. */
    private record Line(String result, String description) {}

    @Test
    void everyCheckPassesOnABootstrappedDatabase() {
        Function<JdbcClient, List<Line>> verify = BootstrapVerifyTests::verify;
        List<Line> lines = postgres.rolledBack(verify);

        assertThat(lines)
                .hasSizeGreaterThan(10)
                .allSatisfy(
                        line -> assertThat(line.result()).as(line.description()).isEqualTo("PASS"));
        assertThat(lines.getLast().description()).matches("all \\d+ checks \\(0 failed\\)");
    }

    @Test
    void theRowLevelSecurityChecksFailWhenARoleIsWrong() {
        for (String[] wrong : new String[][] {
            {"alter role migrator nobypassrls", "migrator has BYPASSRLS"},
            {"alter role app_user bypassrls", "app_user does not have BYPASSRLS"},
            {"alter role jobrunr_user bypassrls", "jobrunr_user does not have BYPASSRLS"}
        }) {
            Function<JdbcClient, List<Line>> verifyAfterwards = jdbc -> {
                jdbc.sql(wrong[0]).update();
                return verify(jdbc);
            };
            List<Line> lines = postgres.rolledBack(verifyAfterwards);
            assertThat(lines).as(wrong[0]).contains(new Line("FAIL", wrong[1]));
            assertThat(lines.getLast().result()).as(wrong[0]).isEqualTo("FAIL");
        }
    }

    /** The script's one query, run inside the caller's transaction (its own begin read only and rollback stripped). */
    private static List<Line> verify(JdbcClient jdbc) {
        String script;
        try {
            script = Files.readString(VERIFY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String query = script.substring(script.indexOf("with checks"), script.lastIndexOf("rollback;"))
                .trim();
        return jdbc.sql(query.substring(0, query.length() - 1))
                .query((row, n) -> new Line(row.getString("result"), row.getString("description")))
                .list();
    }
}
