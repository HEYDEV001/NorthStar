package com.northstar.contacts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.northstar.contacts.TemplateParameters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A template message's text as it is sent (step 2.9a): the body with its variables filled, the values matching the
 * variables exactly, and nothing rendered for a template Meta has not given a body and variables for.
 */
class TemplateTextTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void positionalValuesFillTheirNumbersWhereverTheyAppear() {
        assertThat(render(
                        "Hi {{1}}, your shoot is on {{2}} at {{ 3 }}. See you, {{1}}!",
                        positional("1", "2", "3"), TemplateParameters.positional("Asha", "Saturday", "10:00")))
                .isEqualTo("Hi Asha, your shoot is on Saturday at 10:00. See you, Asha!");
    }

    @Test
    void namedValuesFillTheirNames() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("shoot_type", "wedding");
        values.put("first_name", "Asha");
        assertThat(render(
                        "Hello {{first_name}}, your {{shoot_type}} is booked.",
                        named("first_name", "shoot_type"),
                        TemplateParameters.named(values)))
                .isEqualTo("Hello Asha, your wedding is booked.");
    }

    @Test
    void valuesAreInsertedAsTheyAreAndPlaceholdersThatAreNotVariablesStay() {
        assertThat(render(
                        "Pay {{1}} by {{2}}", positional("1", "2"), TemplateParameters.positional("$5 \\ {{2}}", "$1")))
                .as("a value is never read as a pattern or as another placeholder")
                .isEqualTo("Pay $5 \\ {{2}} by $1");
        assertThat(render(
                        "Hi {{first_name}}, ref {{1}} and {{order2}}",
                        named("first_name"), TemplateParameters.named(Map.of("first_name", "Asha"))))
                .as("a placeholder of the other format, or with a digit in its name, is not a variable")
                .isEqualTo("Hi Asha, ref {{1}} and {{order2}}");
        assertThat(render("Thanks for booking", positional(), TemplateParameters.none()))
                .isEqualTo("Thanks for booking");
        assertThat(render("Thanks for booking", named(), TemplateParameters.none()))
                .isEqualTo("Thanks for booking");
    }

    @Test
    void valuesThatDoNotMatchTheVariablesAreRefusedWithoutRepeatingThem() {
        List<Runnable> mismatches = List.of(
                () -> render("Hi {{1}} {{2}}", positional("1", "2"), TemplateParameters.positional("Asha")),
                () -> render("Hi {{1}}", positional("1"), TemplateParameters.positional("Asha", "secret-extra")),
                () -> render("Hi {{1}}", positional("1"), TemplateParameters.named(Map.of("first_name", "Asha"))),
                () -> render(
                        "Hi {{first_name}}", named("first_name"), TemplateParameters.named(Map.of("name", "Asha"))),
                () -> render(
                        "Hi {{first_name}}",
                        named("first_name"),
                        TemplateParameters.named(Map.of("first_name", "Asha", "extra", "secret-extra"))),
                () -> render("Hi {{first_name}}", named("first_name"), TemplateParameters.positional("Asha")),
                () -> render("Thanks", named(), TemplateParameters.named(Map.of("first_name", "Asha"))),
                () -> render("Hi {{1}} {{3}}", positional("1", "3"), TemplateParameters.positional("a", "b")));
        for (Runnable mismatch : mismatches) {
            assertThatThrownBy(mismatch::run)
                    .isInstanceOf(IllegalArgumentException.class)
                    .message()
                    .doesNotContain("Asha", "secret-extra");
        }
    }

    @Test
    void aTemplateWithoutABodyOrVariablesHasNoText() {
        assertThat(TemplateText.render(null, positional("1"), TemplateParameters.positional("Asha"), JSON))
                .isNull();
        assertThat(TemplateText.render("Hello {{1}}", null, TemplateParameters.named(Map.of("x", "unchecked")), JSON))
                .as("variables nobody has read from Meta: no text, and the values are not checked")
                .isNull();
    }

    private static String render(String body, String variables, TemplateParameters values) {
        return TemplateText.render(body, variables, values, JSON);
    }

    private static String positional(String... names) {
        return variables("positional", names);
    }

    private static String named(String... names) {
        return variables("named", names);
    }

    private static String variables(String format, String... names) {
        return JSON.writeValueAsString(Map.of("format", format, "names", List.of(names)));
    }
}
