package com.northstar.contacts.internal;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How a message and an opt-out keyword are compared (step 2.5): both are normalised the same way and must then be equal.
 * Never logged: the input is message text or a keyword a business chose.
 */
final class OptOutText {

    private OptOutText() {}

    /**
     * Unicode NFKC (so full-width and other compatibility forms fold to their plain letters), lowercased with the root
     * locale, invisible format characters (such as a zero-width space) removed, punctuation, control characters and
     * every kind of white space turned into a gap, each run of gaps made one space, and trimmed. Null gives "".
     */
    static String normalise(String text) {
        if (text == null) return "";
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder(folded.length());
        boolean gap = false;
        for (int codePoint : folded.codePoints().toArray()) {
            if (Character.getType(codePoint) == Character.FORMAT) continue;
            if (isGap(codePoint)) {
                gap = !kept.isEmpty();
                continue;
            }
            if (gap) kept.append(' ');
            kept.appendCodePoint(codePoint);
            gap = false;
        }
        return kept.toString();
    }

    private static boolean isGap(int codePoint) {
        if (Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.isISOControl(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }
}
