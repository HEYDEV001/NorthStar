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
 * or fully qualified reference outside comments and literals. Two rules: module dependencies point one way (no cycle, however long),
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
        // Step 2.4b: the inbound job in integrations writes through contacts and workflow, which stay independent of
        // each other and of integrations, so later steps can depend on either without a cycle.
        assertThat(graph.get("integrations")).contains("contacts", "workflow");
        assertThat(graph.get("contacts")).contains("tenancy").doesNotContain("workflow", "integrations");
        assertThat(graph.get("workflow")).doesNotContain("contacts", "integrations");
        // Step 2.5: the action core asks for consent through its own port, RecipientConsent, which integrations
        // implements over contacts; contacts reads the tenant's keywords from tenancy, which depends on neither.
        assertThat(graph.get("integrations")).contains("contacts", "workflow");
        assertThat(graph.get("contacts")).contains("tenancy", "api");
        assertThat(graph.getOrDefault("tenancy", Set.of())).doesNotContain("contacts");
    }

    @Test
    void theScanReadsMegabyteCommentsStringsAndTextBlocks() {
        String hidden = "com.northstar.admin.internal.Hidden ' /* // ";
        String source = String.join(
                "\n",
                "package com.northstar.jobs;",
                "/*" + megabyteOf(hidden + "\" \n") + "*/",
                "import com.northstar.tenancy.TenantScope;",
                "// " + megabyteOf(hidden + "\" "),
                "class Synthetic {",
                "    String string = \"" + megabyteOf(hidden + "\\\" ") + "\";",
                "    String textBlock = \"\"\"",
                megabyteOf(hidden + "\" \\\"\"\" \n") + "\"\"\";",
                "    char quote = '\"', apostrophe = '\\'';",
                "    Object engine = com.northstar.workflow.WorkflowService.class;",
                "}");

        List<String> targets = uses("jobs", Path.of("Synthetic.java"), source).stream()
                .map(Use::target)
                .toList();

        assertThat(targets)
                .as("only the references in code, none of the ones inside the comments and literals")
                .containsExactly("com.northstar.tenancy.TenantScope", "com.northstar.workflow.WorkflowService.class");
    }

    @Test
    void theScannerKeepsCodeThatOnlyLooksLikeACommentOrALiteral() {
        // SecurityConfiguration's actuator matcher: the "/**" in the string opens no comment (the CI overflow).
        assertThat(withoutCommentsOrLiterals("matcher(\"/actuator/health/**\");\n/** Doc. */\nuse(com.northstar.a.B);"))
                .isEqualTo("matcher(\"\");\n \nuse(com.northstar.a.B);");
        assertThat(withoutCommentsOrLiterals("a(\"*/ // \\\" /*\", '\"', '\\'', \"\\\\\"); b();"))
                .isEqualTo("a(\"\", '', '', \"\"); b();");
        assertThat(withoutCommentsOrLiterals("/* \" ' */ x(); // \" '\ny();")).isEqualTo("  x();  \ny();");
        assertThat(withoutCommentsOrLiterals("t = \"\"\"\n  say \"hi\" \\\"\"\" //\n  \"\"\"; z();"))
                .as("a text block keeps its line breaks and ends at its first unescaped \"\"\"")
                .isEqualTo("t = \"\"\n\n; z();");
        assertThat(withoutCommentsOrLiterals("s = \"never closed\nnext();"))
                .as("an unterminated string stops at its line end")
                .isEqualTo("s = \"\"\nnext();");
    }

    private static String megabyteOf(String text) {
        return text.repeat((1 << 20) / text.length() + 1);
    }

    private static List<Use> scan() {
        List<Use> uses = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String module = MAIN.relativize(file).getName(0).toString();
                if (module.endsWith(".java")) continue; // the application class at the root
                uses.addAll(uses(module, file, Files.readString(file)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return uses;
    }

    /** The other modules one file of this module uses. */
    private static List<Use> uses(String module, Path file, String source) {
        List<Use> uses = new ArrayList<>();
        for (String line : withoutCommentsOrLiterals(source).lines().toList()) {
            Matcher reference = REFERENCE.matcher(line);
            while (reference.find()) {
                String target = reference.group(1);
                if (target.equals(module)) continue;
                String rest = reference.group(2);
                boolean internal = rest.startsWith(".internal");
                uses.add(new Use(module, target, file, "com.northstar." + target + rest, internal));
            }
        }
        return uses;
    }

    /**
     * The source with comments removed and every literal emptied: a package name in a string, such as TenantContext's
     * "com.northstar.jobs", is not a dependency. One pass from left to right, so whatever opens first wins, as in the
     * compiler: the "/**" in a string such as "/actuator/health/**" starts no comment, and the quote in a comment or a
     * '"' starts no string. Each comment becomes one space and each literal "" or '', keeping their line breaks so lines
     * stay where they were. It only loops, never recurses, so no input can overflow the stack (the regex replacements
     * it replaces recursed once per character of a string and did, on CI). Unicode escapes are not translated; main
     * code has none.
     */
    static String withoutCommentsOrLiterals(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int length = source.length();
        int at = 0;
        while (at < length) {
            int end;
            if (source.startsWith("//", at)) {
                end = source.indexOf('\n', at);
                if (end < 0) end = length;
                code.append(' '); // the line break itself is code, appended on the next turn
            } else if (source.startsWith("/*", at)) {
                end = source.indexOf("*/", at + 2);
                end = end < 0 ? length : end + 2;
                code.append(' ');
            } else if (source.startsWith("\"\"\"", at)) {
                end = textBlockEnd(source, at + 3);
                code.append("\"\"");
            } else if (source.charAt(at) == '"' || source.charAt(at) == '\'') {
                char quote = source.charAt(at);
                end = literalEnd(source, at + 1, quote);
                code.append(quote).append(quote);
            } else {
                code.append(source.charAt(at++));
                continue;
            }
            for (int i = at; i < end; i++) if (source.charAt(i) == '\n') code.append('\n');
            at = end;
        }
        return code.toString();
    }

    /** Just past the """ that closes a text block whose content starts at {@code from}. */
    private static int textBlockEnd(String source, int from) {
        int at = from;
        while (at < source.length()) {
            if (source.charAt(at) == '\\') at += 2; // an escape, such as \" or \"""
            else if (source.startsWith("\"\"\"", at)) return at + 3;
            else at++;
        }
        return source.length();
    }

    /** Just past the quote that closes a string or char literal whose content starts at {@code from}. */
    private static int literalEnd(String source, int from, char quote) {
        int at = from;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (c == '\\') at += 2;
            else if (c == quote) return at + 1;
            else if (c == '\n') return at; // unterminated: it cannot run past its line
            else at++;
        }
        return source.length();
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
