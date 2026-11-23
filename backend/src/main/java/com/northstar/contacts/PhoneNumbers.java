package com.northstar.contacts;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone numbers as contacts store them: E.164, a plus sign and 7 to 15 digits with no leading zero (data-model.md
 * section 4), the same check as {@code contacts.normalized_phone} in the database. A number is never logged in full.
 */
public final class PhoneNumbers {

    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");

    /** India's country code. */
    private static final String INDIA = "+91";

    /** A number under +91: exactly 10 digits after it, the first 1 to 9 (a mobile or a landline, never a trunk 0). */
    private static final Pattern INDIAN_E164 = Pattern.compile("\\+91[1-9][0-9]{9}");

    /** A 10-digit Indian mobile (6 to 9 first), alone or after +91, 91 or a trunk 0, without spaces and dashes. */
    private static final Pattern INDIAN_MOBILE = Pattern.compile("(?:\\+91|91|0)?([6-9][0-9]{9})");

    private static final Pattern SEPARATORS = Pattern.compile("[ -]");

    /** Longer input is refused before it is looked at. */
    public static final int MAX_INPUT_LENGTH = 32;

    private PhoneNumbers() {}

    /** True for an E.164 number such as {@code +919876543210}. */
    public static boolean isE164(String phone) {
        return phone != null && E164.matcher(phone).matches();
    }

    /**
     * A number a member typed (step 2.10, P4), as E.164, or empty when it is not one of the accepted forms:
     *
     * <ul>
     *   <li>a number already in E.164, kept as it is, except that one under +91 must have exactly 10 digits after it,
     *       the first 1 to 9 (landlines included: WhatsApp Business runs on some; never a trunk 0);
     *   <li>a 10-digit Indian mobile starting with 6, 7, 8 or 9, with spaces and dashes allowed and an optional +91,
     *       91 or 0 in front, which becomes +91 and the 10 digits.
     * </ul>
     *
     * Surrounding white space is ignored; anything else (letters, brackets, dots, other separators, other lengths) is
     * refused. Never logs the input.
     */
    public static Optional<String> normalize(String input) {
        if (input == null || input.length() > MAX_INPUT_LENGTH) return Optional.empty();
        String trimmed = input.strip();
        if (isE164(trimmed)) {
            boolean indian = trimmed.startsWith(INDIA);
            return indian && !INDIAN_E164.matcher(trimmed).matches() ? Optional.empty() : Optional.of(trimmed);
        }
        Matcher mobile = INDIAN_MOBILE.matcher(SEPARATORS.matcher(trimmed).replaceAll(""));
        return mobile.matches() ? Optional.of(INDIA + mobile.group(1)) : Optional.empty();
    }
}
