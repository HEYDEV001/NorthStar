package com.northstar.contacts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The values a WhatsApp template is filled with when it is sent (step 2.6a), stored on the message as
 * {@code messages.template_params}: positional ({{1}}, {{2}} ... in order) or named ({{first_name}} ...), the two
 * formats Meta's templates use. Values are message text: stored, never logged, so toString shows nothing of them.
 */
public sealed interface TemplateParameters {

    /**
     * Meta's rule for a named parameter: lowercase characters and underscores (Meta: template fundamentals, parameter
     * formats; integration-specs.md section 2).
     */
    Pattern NAMED_PARAMETER = Pattern.compile("[a-z_]+");

    /** Values for {{1}}, {{2}} ... in order. A template with no variables takes none. */
    record Positional(List<String> values) implements TemplateParameters {
        public Positional {
            values = List.copyOf(values); // refuses null values
        }

        @Override
        public String toString() {
            return "TemplateParameters.Positional[" + values.size() + " redacted]";
        }
    }

    /** Values by parameter name. */
    record Named(Map<String, String> values) implements TemplateParameters {
        public Named {
            Objects.requireNonNull(values, "values");
            Map<String, String> copy = new LinkedHashMap<>();
            values.forEach((name, value) -> {
                if (name == null || !NAMED_PARAMETER.matcher(name).matches()) {
                    throw new IllegalArgumentException("A named parameter is lowercase letters and underscores");
                }
                copy.put(name, Objects.requireNonNull(value, "value"));
            });
            if (copy.isEmpty()) throw new IllegalArgumentException("Named parameters need at least one value");
            values = Collections.unmodifiableMap(copy);
        }

        @Override
        public String toString() {
            return "TemplateParameters.Named[" + values.size() + " redacted]";
        }
    }

    /** No values: a template without variables. */
    static TemplateParameters none() {
        return new Positional(List.of());
    }

    static TemplateParameters positional(String... values) {
        return new Positional(List.of(values));
    }

    static TemplateParameters named(Map<String, String> values) {
        return new Named(values);
    }
}
