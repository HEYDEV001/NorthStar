package com.northstar.workflow.internal;

import com.northstar.workflow.ConfigProblem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A workflow config as JSON text (workflow-spec.md section 15): read strictly, so that what is validated is exactly what
 * is stored. Jackson and Postgres both keep the last of two members with the same name, so a duplicate is refused here
 * instead, with its path; so is anything Postgres cannot store as jsonb (U+0000, a lone surrogate, a number too large
 * for numeric). The text is stored as sent once it passes: jsonb keeps a number's scale, so {@code 4.0} stays a fraction
 * when the stored draft is read back at publish.
 *
 * <p>Not the app's API mapper: a draft is a document the format validates member by member, not a DTO.
 */
final class ConfigJson {

    /** The most a draft may be, as UTF-8 text (workflow-spec.md section 15). */
    static final int MAX_BYTES = 262_144;

    private static final int MAX_NUMBER_DIGITS = 1_000;

    // Trailing tokens are refused by default in Jackson 3. Floats are read as BigDecimal, so 4.0 is never an integer.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private ConfigJson() {}

    /** A draft as sent: the document, or the problems that stop it being stored (never both). */
    record Parsed(ObjectNode document, List<ConfigProblem> refused) {

        boolean isStorable() {
            return document != null;
        }
    }

    /**
     * Reads a draft as sent. Refused, each at {@code $} unless it says otherwise: {@code json_invalid},
     * {@code config_too_large}, {@code config_not_object}, and {@code json_member_duplicate} at every repeated member.
     */
    static Parsed parse(String text) {
        if (text == null) return refused(ProblemCode.JSON_INVALID);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return refused(ProblemCode.CONFIG_TOO_LARGE);
        Problems duplicates = new Problems();
        JsonToken first;
        try {
            first = scan(text, duplicates);
        } catch (JacksonException | NotStorable e) {
            return refused(ProblemCode.JSON_INVALID);
        }
        if (first == null) return refused(ProblemCode.JSON_INVALID); // no JSON at all
        if (first != JsonToken.START_OBJECT) return refused(ProblemCode.CONFIG_NOT_OBJECT);
        if (!duplicates.isEmpty()) return new Parsed(null, duplicates.list());
        try {
            return new Parsed((ObjectNode) MAPPER.readTree(text), List.of());
        } catch (JacksonException e) {
            return refused(ProblemCode.JSON_INVALID);
        }
    }

    /** A config as the database returns it (jsonb text), always a JSON object (the shape checks). */
    static ObjectNode stored(String text) {
        JsonNode node = MAPPER.readTree(text);
        if (!(node instanceof ObjectNode document)) throw new IllegalStateException("A stored config is an object");
        return document;
    }

    static String write(JsonNode document) {
        return MAPPER.writeValueAsString(document);
    }

    /** SHA-256 of the stored JSON text, lowercase hex: what a reviewer saw, compared again at publish. */
    static String hash(String storedText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(storedText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private static Parsed refused(ProblemCode code) {
        return new Parsed(null, List.of(new ConfigProblem(JsonPath.ROOT.toString(), code.code())));
    }

    /** One level of the document being scanned: an object (with the names seen so far) or a list. */
    private static final class Level {
        final JsonPath path;
        final boolean object;
        final Set<String> names = new HashSet<>();
        String name;
        int index;

        Level(JsonPath path, boolean object) {
            this.path = path;
            this.object = object;
        }

        JsonPath next() {
            return object ? path.member(name) : path.index(index++);
        }
    }

    private static final class NotStorable extends RuntimeException {
        NotStorable() {
            super(null, null, false, false);
        }
    }

    /** Walks every token once: the whole text must be JSON, and every repeated member is recorded. */
    private static JsonToken scan(String text, Problems duplicates) {
        Deque<Level> levels = new ArrayDeque<>();
        JsonToken first = null;
        try (JsonParser parser = MAPPER.createParser(text)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (first == null) first = token;
                switch (token) {
                    case START_OBJECT, START_ARRAY -> {
                        JsonPath path =
                                levels.isEmpty() ? JsonPath.ROOT : levels.peek().next();
                        levels.push(new Level(path, token == JsonToken.START_OBJECT));
                    }
                    case END_OBJECT, END_ARRAY -> levels.pop();
                    case PROPERTY_NAME -> {
                        String name = storable(parser.currentName());
                        Level level = levels.peek();
                        level.name = name;
                        if (!level.names.add(name))
                            duplicates.add(level.path.member(name), ProblemCode.JSON_MEMBER_DUPLICATE);
                    }
                    case VALUE_STRING -> {
                        storable(parser.getString());
                        if (!levels.isEmpty()) levels.peek().next();
                    }
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                        storableNumber(parser.getText());
                        if (!levels.isEmpty()) levels.peek().next();
                    }
                    default -> {
                        if (!levels.isEmpty()) levels.peek().next();
                    }
                }
            }
        }
        return first;
    }

    /** Postgres refuses U+0000 and unpaired surrogates in jsonb. */
    private static String storable(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u0000') throw new NotStorable();
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) throw new NotStorable();
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new NotStorable();
            }
        }
        return value;
    }

    /** At most 1,000 digits before and after the point, well inside what Postgres numeric holds. */
    private static void storableNumber(String text) {
        BigDecimal number;
        try {
            number = new BigDecimal(text);
        } catch (NumberFormatException exponentTooLarge) {
            throw new NotStorable();
        }
        if (number.scale() > MAX_NUMBER_DIGITS || (long) number.precision() - number.scale() > MAX_NUMBER_DIGITS) {
            throw new NotStorable();
        }
    }
}
