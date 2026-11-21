package com.northstar.contacts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.contacts.ContactConsent;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * How a message and an opt-out keyword are normalised before they are compared (step 2.5, W32): the whole text must
 * equal a keyword, so normalising may only fold case, compatibility forms, invisible characters, punctuation and
 * spacing, never drop or reorder words.
 */
class OptOutTextTests {

    @Test
    void W32_caseSpacingAndPunctuationAreFoldedAndWordsAreKept() {
        assertThat(OptOutText.normalise("STOP")).isEqualTo("stop");
        assertThat(OptOutText.normalise("  Stop  ")).isEqualTo("stop");
        assertThat(OptOutText.normalise("Stop!")).isEqualTo("stop");
        assertThat(OptOutText.normalise("¡¿Stop?!")).isEqualTo("stop");
        assertThat(OptOutText.normalise("\"stop\"")).isEqualTo("stop");
        assertThat(OptOutText.normalise("«stop»")).isEqualTo("stop");
        assertThat(OptOutText.normalise("STOP\n")).isEqualTo("stop");
        assertThat(OptOutText.normalise("stop\tpromotions")).isEqualTo("stop promotions");
        assertThat(OptOutText.normalise("Stop   promotions.")).isEqualTo("stop promotions");
        assertThat(OptOutText.normalise("Please don't stop the shoot"))
                .as("every word is kept, so a sentence never equals a keyword")
                .isEqualTo("please don t stop the shoot");
    }

    @Test
    void W32_punctuationBetweenWordsBecomesASpace() {
        assertThat(OptOutText.normalise("Opt-out.")).isEqualTo("opt out");
        assertThat(OptOutText.normalise("opt_out")).isEqualTo("opt out");
        assertThat(OptOutText.normalise("opt—out")).as("an em dash").isEqualTo("opt out");
        assertThat(OptOutText.normalise("opt.out")).isEqualTo("opt out");
        assertThat(OptOutText.normalise("optout")).as("one word stays one word").isEqualTo("optout");
        assertThat(OptOutText.normalise("s.t.o.p")).isEqualTo("s t o p");
    }

    @Test
    void W32_compatibilityFormsAndUnicodeSpacesFold() {
        assertThat(OptOutText.normalise("ＳＴＯＰ")).as("full-width letters").isEqualTo("stop");
        assertThat(OptOutText.normalise("stop promotions"))
                .as("a no-break space")
                .isEqualTo("stop promotions");
        assertThat(OptOutText.normalise("stop　promotions"))
                .as("an ideographic space")
                .isEqualTo("stop promotions");
        assertThat(OptOutText.normalise("st​op﻿"))
                .as("invisible format characters")
                .isEqualTo("stop");
        assertThat(OptOutText.normalise("STOP\u0000")).as("a control character").isEqualTo("stop");
    }

    @Test
    void W32_lowercasingIgnoresTheDefaultLocale() {
        // Under a Turkish default locale, "I".toLowerCase() is a dotless i, so "UNSUBSCRIBE" would never match.
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat("UNSUBSCRIBE".toLowerCase()).as("the trap").isNotEqualTo("unsubscribe");
            assertThat(OptOutText.normalise("UNSUBSCRIBE")).isEqualTo("unsubscribe");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void W32_symbolsAndEmojiAreKeptSoTheyNeverMatchAKeyword() {
        assertThat(OptOutText.normalise("stop 🛑")).isEqualTo("stop 🛑");
        assertThat(OptOutText.normalise("stop+")).isEqualTo("stop+");
    }

    @Test
    void W32_otherScriptsKeepTheirLettersAndLoseTheirPunctuation() {
        assertThat(OptOutText.normalise("बंद करो।"))
                .as("the Devanagari danda is punctuation")
                .isEqualTo("बंद करो");
        assertThat(OptOutText.normalise("  बंद   करो ")).isEqualTo("बंद करो");
    }

    @Test
    void W32_textWithNothingLeftNormalisesToEmpty() {
        assertThat(OptOutText.normalise(null)).isEmpty();
        assertThat(OptOutText.normalise("")).isEmpty();
        assertThat(OptOutText.normalise("   ")).isEmpty();
        assertThat(OptOutText.normalise("?!...")).isEmpty();
        assertThat(OptOutText.normalise("​")).isEmpty();
    }

    @Test
    void W32_thePlatformKeywordsAreTheDecidedOnesAndAlreadyNormalised() {
        assertThat(ContactConsent.PLATFORM_OPT_OUT_KEYWORDS)
                .containsExactly("stop", "unsubscribe", "opt out", "optout", "stop promotions");
        for (String keyword : ContactConsent.PLATFORM_OPT_OUT_KEYWORDS) {
            assertThat(OptOutText.normalise(keyword)).isEqualTo(keyword);
        }
    }
}
