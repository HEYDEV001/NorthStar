package com.northstar.workflow.internal;

import java.util.regex.Pattern;

/**
 * Where a problem is in a config (workflow-spec.md section 15): {@code $} for the document, {@code .name} for a member
 * whose name is letters, digits and {@code _} not starting with a digit, {@code ['name']} for any other (with
 * {@code \} and {@code '} escaped), and {@code [n]} for a list item, counted from 0.
 */
final class JsonPath {

    static final JsonPath ROOT = new JsonPath("$");

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String text;

    private JsonPath(String text) {
        this.text = text;
    }

    JsonPath member(String name) {
        if (PLAIN.matcher(name).matches()) return new JsonPath(text + "." + name);
        return new JsonPath(text + "['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']");
    }

    JsonPath index(int index) {
        return new JsonPath(text + "[" + index + "]");
    }

    @Override
    public String toString() {
        return text;
    }
}
