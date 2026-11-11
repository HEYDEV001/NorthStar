package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Module boundaries in main code (CLAUDE.md, repository layout), read from the source: every com.northstar.* import
 * or fully qualified reference outside comments. Two rules: module dependencies point one way (no cycle, however long),
 * and no module uses another module's .internal package. Test code is not checked; tests reach across on purpose. A
 * dependency made only through reflection would not show; main code has none.
 */
class ModuleDependencyTests {

    private static final Path MAIN = Path.of("src/main/java/com/northstar");
    private static final Pattern REFERENCE = Pattern.compile("com\\.northstar\\.(\\w+)((?:\\.\\w+)*)");

    /** A use of one module by another: the file, and the referenced package or class. */
    private record Use(String fromModule, String toModule, Path file, String target, boolean internal) {}

    @Test
    void modulesDependOneWayAndOnlyOnPublicPackages() {
        List<Use> uses = scan();
        Map<String, Set<String>> graph = new TreeMap<>();
        for (Use use : uses)
            graph.computeIfAbsent(use.fromModule(), m -> new TreeSet<>()).add(use.toModule());

        List<String> internalUses = uses.stream()
                .filter(Use::internal)
                .map(use -> MAIN.relativize(use.file()) + " uses " + use.target())
                .distinct()
                .sorted()
                .toList();
        List<List<String>> cycles = cycles(graph);

        System.out.println(report(graph, internalUses, cycles));
        assertThat(cycles).as("module dependency cycles").isEmpty();
        assertThat(internalUses)
                .as("uses of another module's .internal package")
                .isEmpty();
    }

    @Test
    void theScanSeesTheKnownDependencies() {
        Map<String, Set<String>> graph = new TreeMap<>();
        for (Use use : scan())
            graph.computeIfAbsent(use.fromModule(), m -> new TreeSet<>()).add(use.toModule());
        assertThat(graph.get("workflow")).contains("tenancy", "jobs");
        assertThat(graph.get("notifications")).contains("tenancy", "workflow", "integrations");
        assertThat(graph.getOrDefault("tenancy", Set.of())).doesNotContain("workflow", "integrations", "notifications");
    }

    private static List<Use> scan() {
        List<Use> uses = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String module = MAIN.relativize(file).getName(0).toString();
                if (module.endsWith(".java")) continue; // the application class at the root
                for (String line : codeLines(file)) {
                    Matcher reference = REFERENCE.matcher(line);
                    while (reference.find()) {
                        String target = reference.group(1);
                        if (target.equals(module)) continue;
                        String rest = reference.group(2);
                        boolean internal = rest.startsWith(".internal");
                        uses.add(new Use(module, target, file, "com.northstar." + target + rest, internal));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return uses;
    }

    /**
     * The file's code with comments and string literals removed (text blocks, strings, Javadoc, block and line
     * comments): a package name in a string, such as TenantContext's "com.northstar.jobs", is not a dependency.
     */
    private static List<String> codeLines(Path file) throws IOException {
        String source = Files.readString(file)
                .replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"")
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("(?m)//.*$", "");
        return source.lines().filter(line -> !line.isBlank()).toList();
    }

    /** Every cycle, each as a path that starts and ends at the same module. */
    private static List<List<String>> cycles(Map<String, Set<String>> graph) {
        List<List<String>> found = new ArrayList<>();
        for (String start : graph.keySet()) walk(graph, start, new LinkedHashSet<>(List.of(start)), start, found);
        return found;
    }

    private static void walk(
            Map<String, Set<String>> graph,
            String start,
            LinkedHashSet<String> path,
            String at,
            List<List<String>> found) {
        for (String next : graph.getOrDefault(at, Set.of())) {
            if (next.equals(start)) {
                List<String> cycle = new ArrayList<>(path);
                cycle.add(start);
                if (cycle.stream().allMatch(m -> start.compareTo(m) <= 0)) found.add(cycle); // report each cycle once
            } else if (!path.contains(next)) {
                path.add(next);
                walk(graph, start, path, next, found);
                path.remove(next);
            }
        }
    }

    private static String report(Map<String, Set<String>> graph, List<String> internalUses, List<List<String>> cycles) {
        StringBuilder report = new StringBuilder("Module dependencies (main code):\n");
        graph.forEach((module, deps) -> report.append("  ")
                .append(module)
                .append(" -> ")
                .append(String.join(", ", deps))
                .append('\n'));
        report.append("Cycles: ").append(cycles.isEmpty() ? "none" : cycles).append('\n');
        report.append("Uses of another module's .internal: ")
                .append(internalUses.isEmpty() ? "none" : String.join("; ", internalUses));
        return report.toString();
    }
}
