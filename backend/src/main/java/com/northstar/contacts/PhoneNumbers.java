package com.northstar.contacts;

import java.util.regex.Pattern;

/**
 * Phone numbers as contacts store them: E.164, a plus sign and 7 to 15 digits with no leading zero (data-model.md
 * section 4), the same check as {@code contacts.normalized_phone} in the database. A number is never logged in full.
 */
public final class PhoneNumbers {

    private static final Pattern E164 = Pattern.compile("\\+[1-9][0-9]{6,14}");

    private PhoneNumbers() {}

    /** True for an E.164 number such as {@code +919876543210}. */
    public static boolean isE164(String phone) {
        return phone != null && E164.matcher(phone).matches();
    }
}
