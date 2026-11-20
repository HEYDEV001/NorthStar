package com.northstar.contacts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.contacts.InboundContacts;
import org.junit.jupiter.api.Test;

/** How a WhatsApp profile name is kept when it fills an empty contact name (step 2.4b, decided 20 Nov 2026). */
class ContactNameTests {

    @Test
    void controlCharactersAndRunsOfSpaceBecomeOneSpaceAndTheEndsAreTrimmed() {
        assertThat(InboundContactsService.cleanName("  Asha \t Rao\u0007  ")).isEqualTo("Asha Rao");
        // A control character inside a word is a break too; a zero-width space is not white space, so it stays.
        assertThat(InboundContactsService.cleanName("Asha\nRao\u0000​X")).isEqualTo("Asha Rao ​X");
        assertThat(InboundContactsService.cleanName("Asha Rao")).isEqualTo("Asha Rao");
    }

    @Test
    void nothingLeftIsNoName() {
        assertThat(InboundContactsService.cleanName(null)).isNull();
        assertThat(InboundContactsService.cleanName("")).isNull();
        assertThat(InboundContactsService.cleanName(" \t\u0001 ")).isNull();
    }

    @Test
    void longNamesAreCutAtTheLimitWithoutSplittingACharacter() {
        String emoji = "👩"; // one code point, two chars
        String name = InboundContactsService.cleanName(emoji.repeat(InboundContacts.MAX_NAME_LENGTH + 10));
        assertThat(name.codePointCount(0, name.length())).isEqualTo(InboundContacts.MAX_NAME_LENGTH);
        assertThat(name).isEqualTo(emoji.repeat(InboundContacts.MAX_NAME_LENGTH));

        String words = InboundContactsService.cleanName("a ".repeat(150));
        assertThat(words.codePointCount(0, words.length())).isLessThanOrEqualTo(InboundContacts.MAX_NAME_LENGTH);
        assertThat(words).doesNotEndWith(" ");
    }

    @Test
    void emojiSequencesAreKeptWhole() {
        String family = "👨‍👩‍👧"; // joined with zero-width joiners
        assertThat(InboundContactsService.cleanName("The " + family + " family"))
                .isEqualTo("The " + family + " family");
    }
}
