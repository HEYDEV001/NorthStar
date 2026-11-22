package com.northstar.integrations.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A template's variables, read from the body Meta returns (step 2.8b; data-model.md section 4): the stored
 * {@code variables} is {@code {"format": "positional"|"named", "names": [...]}}. The format is Meta's
 * {@code parameter_format} (POSITIONAL when absent, as for templates made before named parameters); the names are the
 * body's own placeholders of that format, positional ones as their numbers in order, named ones in the order they first
 * appear. A placeholder of the other format is not a variable. Nothing is invented: a body without placeholders has no
 * names.
 */
final class TemplateVariables {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}\\s]{1,64})\\s*}}");
    private static final Pattern POSITIONAL = Pattern.compile("[1-9][0-9]{0,2}");

    /** Meta's named parameters: lowercase letters and underscores, with digits after the first character. */
    private static final Pattern NAMED = Pattern.compile("[a-z_][a-z0-9_]{0,63}");

    private TemplateVariables() {}

    /** The variables as JSON, or null when there is no body to read them from. */
    static String json(String parameterFormat, String body) {
        if (body == null) return null;
        boolean named = "NAMED".equals(parameterFormat);
        Set<String> names = new LinkedHashSet<>();
        Matcher placeholder = PLACEHOLDER.matcher(body);
        while (placeholder.find()) {
            String name = placeholder.group(1);
            if ((named ? NAMED : POSITIONAL).matcher(name).matches()) names.add(name);
        }
        List<String> ordered = new ArrayList<>(names);
        if (!named) ordered.sort(Comparator.comparingInt(Integer::parseInt));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("format", named ? "named" : "positional");
        variables.put("names", ordered);
        return ProviderJson.MAPPER.writeValueAsString(variables);
    }
}
