package com.northstar.devtools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A gate tool's {@code --name=value} arguments (step 2.11). Unknown, repeated and malformed ones are refused by name; a
 * value is never repeated in a message, as it could be something that should not be shown.
 */
final class ToolArguments {

    private final Map<String, String> values;

    private ToolArguments(Map<String, String> values) {
        this.values = values;
    }

    static ToolArguments parse(List<String> arguments, String... allowed) {
        TreeSet<String> known = new TreeSet<>(List.of(allowed));
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (!argument.startsWith("--") || equals < 3) {
                throw invalid("Every option is written --name=value.");
            }
            String name = argument.substring(2, equals);
            if (!known.contains(name)) {
                if (name.equals("token") || name.equals("access-token")) {
                    throw invalid("The access token is never an argument: pipe it in, type it at the prompt, or set "
                            + TokenInput.VARIABLE + ".");
                }
                throw invalid("This command has no such option. It takes: --" + String.join(", --", known) + ".");
            }
            if (values.put(name, argument.substring(equals + 1)) != null) {
                throw invalid("--" + name + " was given twice.");
            }
        }
        return new ToolArguments(values);
    }

    Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name)).filter(value -> !value.isBlank());
    }

    String require(String name) {
        return get(name).orElseThrow(() -> invalid("--" + name + "=... is required."));
    }

    UUID requireUuid(String name) {
        try {
            return UUID.fromString(require(name));
        } catch (IllegalArgumentException malformed) {
            throw invalid("--" + name + " must be a UUID.");
        }
    }

    int integer(String name, int byDefault) {
        Optional<String> value = get(name);
        if (value.isEmpty()) return byDefault;
        try {
            return Integer.parseInt(value.get());
        } catch (NumberFormatException malformed) {
            throw invalid("--" + name + " must be a whole number.");
        }
    }

    private static LocalToolException invalid(String message) {
        return new LocalToolException("argument_invalid", message);
    }
}
