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
     * Meta's rule for a named parameter: "unique, single strings, composed of lowercase characters and underscores"
     * (Meta: template fundamentals, parameter formats, re-checked 22 Nov 2026; integration-specs.md section 2), so no
     * digits. Meta lists no length limit; 64 characters is Northstar's cap, the longest placeholder the template reader
     * takes. The one rule for both the values sent and the variables read from a template's body (step 2.9a).
     */
    Pattern NAMED_PARAMETER = Pattern.compile("[a-z_]{1,64}");

    /** A positional parameter's number: {{1}}, {{2}} ... (Meta: "ordered array index numbers, starting from 1"). */
    Pattern POSITIONAL_PARAMETER = Pattern.compile("[1-9][0-9]{0,2}");

    /**
     * A placeholder in a template's body: double curly brackets around one name or number, white space inside allowed.
     * Group 1 is the name or number; whether it is a variable depends on the template's format.
     */
    Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}\\s]{1,64})\\s*}}");

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
                    throw new IllegalArgumentException(
                            "A named parameter is 1 to 64 lowercase letters and underscores");
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
