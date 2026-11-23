package com.northstar.contacts.internal;

import com.northstar.contacts.TemplateParameters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A template message's text as it is sent (step 2.9a): the template's body (Meta's BODY text, step 2.8b) with each of
 * its variables replaced by the value queued for it. Queueing stores it in {@code messages.body}, so the inbox shows
 * what was sent, and retention clears it with every other body (data-model.md section 14).
 *
 * <p>The values must match the template's variables exactly, in the format the template uses: positional values one
 * for each of {{1}} to {{n}}, named values one for each name. A mismatch is refused when the message is queued, as Meta
 * would refuse the send. A placeholder that is not one of the template's variables (one of the other format, or with
 * a name Meta does not allow) is left as written, as Meta leaves it.
 *
 * <p>A template whose body or variables Meta has not given (a draft, or one created during onboarding before the sync
 * reads it) has no text: nothing is rendered and the values are not checked against variables nobody has read.
 *
 * <p>Values are message text: never logged, and never in an exception message.
 */
final class TemplateText {

    private static final Pattern PLACEHOLDER = TemplateParameters.PLACEHOLDER;

    private TemplateText() {}

    /**
     * The rendered text, or null when the template has no body or no variables.
     *
     * @param variablesJson the template's stored {@code variables}: {@code {"format": "positional"|"named", "names":
     *     [...]}} (check {@code message_templates_variables_shape})
     * @throws IllegalArgumentException if the values do not match the template's variables
     */
    static String render(String body, String variablesJson, TemplateParameters values, JsonMapper json) {
        if (body == null || variablesJson == null) return null;
        JsonNode variables = json.readTree(variablesJson);
        boolean named = "named".equals(variables.path("format").stringValue(""));
        List<String> names = variables
                .path("names")
                .valueStream()
                .map(name -> name.stringValue(""))
                .toList();
        Map<String, String> byName = named ? namedValues(names, values) : positionalValues(names, values);

        Matcher placeholder = PLACEHOLDER.matcher(body);
        StringBuilder text = new StringBuilder();
        while (placeholder.find()) {
            String value = byName.get(placeholder.group(1));
            placeholder.appendReplacement(text, Matcher.quoteReplacement(value != null ? value : placeholder.group()));
        }
        placeholder.appendTail(text);
        return text.toString();
    }

    /** {{1}} to {{n}}, one value each, in order. A template's numbers that are not exactly 1 to n cannot be filled. */
    private static Map<String, String> positionalValues(List<String> names, TemplateParameters values) {
        List<String> expected = IntStream.rangeClosed(1, names.size())
                .mapToObj(Integer::toString)
                .toList();
        if (!names.equals(expected)) {
            throw new IllegalArgumentException("The template's positional variables are not numbered 1 to n");
        }
        if (!(values instanceof TemplateParameters.Positional positional)
                || positional.values().size() != names.size()) {
            throw mismatch();
        }
        return IntStream.range(0, names.size()).boxed().collect(Collectors.toMap(names::get, positional.values()::get));
    }

    /** One value for each name, and no other name. A template without names takes no values. */
    private static Map<String, String> namedValues(List<String> names, TemplateParameters values) {
        if (names.isEmpty()) {
            if (values instanceof TemplateParameters.Positional none
                    && none.values().isEmpty()) return Map.of();
            throw mismatch();
        }
        if (!(values instanceof TemplateParameters.Named given)
                || !given.values().keySet().equals(Set.copyOf(names))) {
            throw mismatch();
        }
        return given.values();
    }

    private static IllegalArgumentException mismatch() {
        return new IllegalArgumentException("The template's values do not match its variables");
    }
}
