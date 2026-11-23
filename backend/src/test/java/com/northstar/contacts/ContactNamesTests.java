package com.northstar.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * How a contact name is kept: a WhatsApp profile name filling an empty name (step 2.4b, decided 20 Nov 2026) and a name
 * a member types with a manual lead (step 2.10) are cleaned alike.
 */
class ContactNamesTests {

    @Test
    void controlCharactersAndRunsOfSpaceBecomeOneSpaceAndTheEndsAreTrimmed() {
        assertThat(ContactNames.clean("  Asha \t Rao\u0007  ")).isEqualTo("Asha Rao");
        // A control character inside a word is a break too; a zero-width space is not white space, so it stays.
        assertThat(ContactNames.clean("Asha\nRao\u0000​X")).isEqualTo("Asha Rao ​X");
        assertThat(ContactNames.clean("Asha Rao")).isEqualTo("Asha Rao");
    }

    @Test
    void nothingLeftIsNoName() {
        assertThat(ContactNames.clean(null)).isNull();
        assertThat(ContactNames.clean("")).isNull();
        assertThat(ContactNames.clean(" \t\u0001 ")).isNull();
    }

    @Test
    void longNamesAreCutAtTheLimitWithoutSplittingACharacter() {
        String emoji = "👩"; // one code point, two chars
        String name = ContactNames.clean(emoji.repeat(ContactNames.MAX_LENGTH + 10));
        assertThat(name.codePointCount(0, name.length())).isEqualTo(ContactNames.MAX_LENGTH);
        assertThat(name).isEqualTo(emoji.repeat(ContactNames.MAX_LENGTH));

        String words = ContactNames.clean("a ".repeat(150));
        assertThat(words.codePointCount(0, words.length())).isLessThanOrEqualTo(ContactNames.MAX_LENGTH);
        assertThat(words).doesNotEndWith(" ");
    }

    @Test
    void emojiSequencesAreKeptWhole() {
        String family = "👨‍👩‍👧"; // joined with zero-width joiners
        assertThat(ContactNames.clean("The " + family + " family")).isEqualTo("The " + family + " family");
    }
}
