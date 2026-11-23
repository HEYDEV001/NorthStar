package com.northstar.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The phone numbers manual lead entry accepts (step 2.10, P4; decided 23 Nov 2026): a number already in E.164 (under
 * +91 exactly 10 digits after it, the first 1 to 9, as landlines run WhatsApp Business too), or a 10-digit Indian
 * mobile starting 6 to 9 with spaces, dashes and a +91, 91 or 0 in front, which becomes +91. Everything else is
 * refused.
 */
class PhoneNumbersTests {

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // Already in E.164, kept as it is.
                "+919876543210     | +919876543210",
                "+442079460958     | +442079460958",
                "+14155550123      | +14155550123",
                "+6591234567       | +6591234567",
                // Under +91, 10 digits with any first digit but 0: landlines and toll-free numbers too.
                "+911123456789     | +911123456789",
                "+917312345678     | +917312345678",
                "+918001234567     | +918001234567",
                "+915555555555     | +915555555555",
                // A 10-digit Indian mobile, 6 to 9 first, in the forms people type.
                "9876543210        | +919876543210",
                "6000000000        | +916000000000",
                "7123456789        | +917123456789",
                "8123456789        | +918123456789",
                "98765 43210       | +919876543210",
                "98765-43210       | +919876543210",
                "987 654 3210      | +919876543210",
                "09876543210       | +919876543210",
                "0 98765 43210     | +919876543210",
                "919876543210      | +919876543210",
                "91 98765 43210    | +919876543210",
                "+91 98765 43210   | +919876543210",
                "+91-98765-43210   | +919876543210",
                "+91 9876543210    | +919876543210",
            })
    void P4_acceptedFormsBecomeE164(String typed, String stored) {
        assertThat(PhoneNumbers.normalize(typed)).contains(stored);
        assertThat(PhoneNumbers.isE164(stored)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(
            strings = {
                // +91 in E.164 with other than 10 digits after it, or a trunk 0 first.
                "+910987654321",
                "+910731234567",
                "+91987654321",
                "+9198765432100",
                "+91123456",
                // Short forms keep the 6-to-9 rule, and their length.
                "5876543210",
                "1123456789",
                "0123456789",
                "987654321",
                "98765432100",
                "+91 22 1234 5678",
                "022 1234 5678",
                "+91 0 98765 43210",
                "910 98765 43210",
                "0091 98765 43210",
                "00919876543210",
                // Not E.164, and separators only for Indian mobiles.
                "+44 20 7946 0958",
                "+0123456789",
                "+12345",
                "+1234567890123456",
                "++919876543210",
                "+91+9876543210",
                // Anything but digits, spaces and dashes.
                "(+91) 98765 43210",
                "98765.43210",
                "98765/43210",
                "98765_43210",
                "98765\t43210",
                "+91 98765 4321O",
                "phone: 9876543210",
                "9876543210 ext 2",
                "९८७६५४३२१०", // Devanagari digits
                "９８７６５４３２１０", // fullwidth digits
                "98765 43210", // a no-break space inside
                "98765–43210", // an en dash
                // Blank, and too long to look at.
                " ",
                "\t\n",
                "-",
                "                                 9876543210",
            })
    @NullAndEmptySource
    void P4_everythingElseIsRefused(String typed) {
        assertThat(PhoneNumbers.normalize(typed)).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {" 9876543210 ", "\t+919876543210\n", "  98765 43210"})
    void P4_surroundingWhiteSpaceIsIgnored(String typed) {
        assertThat(PhoneNumbers.normalize(typed)).contains("+919876543210");
    }
}
