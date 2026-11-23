package com.northstar.integrations.internal;

import com.northstar.contacts.TemplateParameters;
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

    // The placeholder syntax and both parameter rules are TemplateParameters', so the variables read here are exactly
    // the names and numbers a send can fill (step 2.9a). Named parameters are lowercase letters and underscores, as
    // Meta's page says: a placeholder with a digit in its name is not a variable.
    private static final Pattern PLACEHOLDER = TemplateParameters.PLACEHOLDER;
    private static final Pattern POSITIONAL = TemplateParameters.POSITIONAL_PARAMETER;
    private static final Pattern NAMED = TemplateParameters.NAMED_PARAMETER;

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
