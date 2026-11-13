package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The cross-tenant CI job runs every P9, P10 and P11 test by name: -Dtest='*Tests#P9_*+P10_*+P11_*' (step 1.14). A
 * case test outside that pattern would silently drop out of the job, so this fails, naming the method, when a P9_,
 * P10_ or P11_ method sits in a class whose name does not end in Tests, or in a nested class (the pattern matches
 * methods of top-level classes only).
 */
class CrossTenantSuiteGuardTests {

    private static final Path TESTS = Path.of("src/test/java");
    private static final Pattern CASE_METHOD =
            Pattern.compile("^(\\s*)(?:public |protected |private )?void (P(?:9|10|11)_\\w+)\\(");

    @Test
    void everyCrossTenantCaseTestIsInTheSuite() {
        List<String> found = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String className = file.getFileName().toString().replace(".java", "");
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher method = CASE_METHOD.matcher(lines.get(i));
                    if (!method.find()) continue;
                    String where = TESTS.relativize(file) + ":" + (i + 1) + " " + method.group(2);
                    found.add(where);
                    if (!className.endsWith("Tests")) {
                        outside.add(where + " is in " + className + ", which does not end in Tests");
                    } else if (method.group(1).length() != 4) {
                        outside.add(where + " is in a nested class; move it to a top-level *Tests class");
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(found).as("P9, P10 and P11 tests").isNotEmpty();
        assertThat(outside).as("case tests the cross-tenant job would not run").isEmpty();
    }
}
