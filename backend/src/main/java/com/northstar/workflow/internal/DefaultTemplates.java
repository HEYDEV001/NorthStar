package com.northstar.workflow.internal;

import com.northstar.workflow.ConfigProblem;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Northstar's default workflow templates (workflow-spec.md section 14, step 3.2). A template that has one is a series of
 * numbered revisions on the classpath, {@code workflow-templates/<template>/<n>.json}, the highest being current. A
 * merged revision is never edited: a change is a new file and a higher number in {@link #REVISIONS}, and every earlier
 * revision is kept. A workflow copies the current revision once, as its draft, when it is created; nothing here changes
 * a business's draft or versions afterwards.
 *
 * <p>Every revision is read and checked against format v1 when the application starts (this constructor reads classpath
 * files only, never the database), so the app does not start with a template that could not be published. Only
 * {@code shoot_production} has one so far; the other industry templates start with no draft.
 */
@Component
class DefaultTemplates {

    /** The current revision of each template that has one; every revision from 1 up to it exists. */
    static final Map<String, Integer> REVISIONS = Map.of("shoot_production", 1);

    private final Map<String, String> current;

    DefaultTemplates() {
        Map<String, String> loaded = new HashMap<>();
        REVISIONS.forEach((template, latest) -> {
            for (int revision = 1; revision <= latest; revision++) {
                String text = read(template, revision);
                if (revision == latest) loaded.put(template, text);
            }
        });
        this.current = Map.copyOf(loaded);
    }

    /** The template's current revision as JSON text, if the template has a default config. */
    Optional<String> current(String template) {
        return template == null ? Optional.empty() : Optional.ofNullable(current.get(template));
    }

    /**
     * A revision's JSON text, checked against format v1 for its template. Fails with IllegalStateException naming the
     * file, and its problems if it has any.
     */
    static String read(String template, int revision) {
        String path = path(template, revision);
        byte[] bytes;
        try (InputStream in = DefaultTemplates.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing workflow template revision " + path);
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read workflow template revision " + path, e);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Workflow template revision " + path + " is not UTF-8", e);
        }
        ConfigJson.Parsed parsed = ConfigJson.parse(text);
        List<ConfigProblem> problems =
                parsed.isStorable() ? ConfigValidator.validate(parsed.document(), template) : parsed.refused();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Workflow template revision " + path + " does not pass format v1: " + problems);
        }
        return text;
    }

    static String path(String template, int revision) {
        return "workflow-templates/" + template + "/" + revision + ".json";
    }
}
