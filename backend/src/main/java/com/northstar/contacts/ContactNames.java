package com.northstar.contacts;

/**
 * A contact's name as kept (data-model.md section 4): the same cleaning for a WhatsApp profile name (step 2.4b) and a
 * name a member types (step 2.10). Names are never logged.
 */
public final class ContactNames {

    /** The longest name kept, in characters (code points). */
    public static final int MAX_LENGTH = 200;

    private ContactNames() {}

    /**
     * Control characters and runs of white space become one space, trimmed, at most {@value #MAX_LENGTH} code points;
     * null when nothing is left.
     */
    public static String clean(String name) {
        if (name == null) return null;
        StringBuilder kept = new StringBuilder();
        int length = 0;
        boolean gap = false;
        for (int codePoint : name.codePoints().toArray()) {
            if (Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)) {
                gap = !kept.isEmpty();
                continue;
            }
            int needed = gap ? 2 : 1;
            if (length + needed > MAX_LENGTH) break;
            if (gap) kept.append(' ');
            kept.appendCodePoint(codePoint);
            length += needed;
            gap = false;
        }
        return kept.isEmpty() ? null : kept.toString();
    }
}
