package com.northstar.integrations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A template's variables, read from the body Meta returns and nothing else (step 2.8b, data-model.md section 4). */
class TemplateVariablesTests {

    @Test
    void positionalPlaceholdersAreTheirNumbersInOrderOnce() {
        assertThat(names("POSITIONAL", "Hi {{1}}, your shoot is on {{2}} at {{ 3 }}. See you, {{1}}!"))
                .isEqualTo("positional:[\"1\",\"2\",\"3\"]");
        assertThat(names("POSITIONAL", "{{10}} then {{2}}")).isEqualTo("positional:[\"2\",\"10\"]");
        assertThat(names(null, "Hi {{1}}"))
                .as("templates made before named parameters have no parameter_format")
                .isEqualTo("positional:[\"1\"]");
    }

    @Test
    void namedPlaceholdersAreTheirNamesInTheOrderTheyFirstAppear() {
        assertThat(names("NAMED", "Hello {{first_name}}, your {{shoot_type}} is booked, {{first_name}}."))
                .isEqualTo("named:[\"first_name\",\"shoot_type\"]");
    }

    @Test
    void nothingIsInventedOrGuessed() {
        assertThat(names("NAMED", "Hello {{1}}"))
                .as("a positional placeholder in a named body")
                .isEqualTo("named:[]");
        assertThat(names("POSITIONAL", "Hello {{name}}")).isEqualTo("positional:[]");
        assertThat(names("NAMED", "Hello {{First Name}} {{first-name}} {{}} {first_name}"))
                .isEqualTo("named:[]");
        assertThat(names("POSITIONAL", "No placeholders at all")).isEqualTo("positional:[]");
        assertThat(TemplateVariables.json("POSITIONAL", null))
                .as("no body, no variables")
                .isNull();
    }

    private static String names(String format, String body) {
        JsonNode json = ProviderJson.MAPPER.readTree(TemplateVariables.json(format, body));
        return json.get("format").stringValue() + ":" + ProviderJson.MAPPER.writeValueAsString(json.get("names"));
    }
}
