package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The slug rules in one place: format, reserved words, generation from a name, and numbered variants. */
class SlugsTests {

    @Test
    void validSlugsAreLowercaseLettersDigitsAndSingleHyphens() {
        assertThat(Slugs.isValid("reelrr")).isTrue();
        assertThat(Slugs.isValid("reelrr-studios-2")).isTrue();
        assertThat(Slugs.isValid("a".repeat(50))).isTrue();

        for (String invalid : new String[] {
            "ab", "a".repeat(51), "Reelrr", "reel_rr", "reel--rr", "-reelrr", "reelrr-", "reel rr", "रील"
        }) {
            assertThat(Slugs.isValid(invalid)).as(invalid).isFalse();
        }
    }

    @Test
    void reservedSlugsAreBlocked() {
        assertThat(Slugs.isReserved("admin")).isTrue();
        assertThat(Slugs.isReserved("www")).isTrue();
        assertThat(Slugs.isReserved("dashboard")).isTrue();
        assertThat(Slugs.isReserved("reelrr")).isFalse();
    }

    @Test
    void generatedFromTheName() {
        assertThat(Slugs.fromName("Reelrr Studios")).isEqualTo("reelrr-studios");
        assertThat(Slugs.fromName("  Café & Crème — Pune!  ")).isEqualTo("cafe-creme-pune");
        assertThat(Slugs.fromName("A".repeat(80))).isEqualTo("a".repeat(50));
        assertThat(Slugs.fromName("Shoot " + "x".repeat(60))).hasSize(50).doesNotEndWith("-");
    }

    @Test
    void aNameWithNothingUsableFallsBackToBusinessAndRandomCharacters() {
        assertThat(Slugs.fromName("रील स्टूडियो")).matches("business-[a-z0-9]{6}");
        assertThat(Slugs.fromName("!!")).matches("business-[a-z0-9]{6}");
    }

    @Test
    void variantsAreNumberedThenRandomAndAlwaysValid() {
        assertThat(Slugs.variant("reelrr", 1)).isEqualTo("reelrr");
        assertThat(Slugs.variant("reelrr", 2)).isEqualTo("reelrr-2");
        assertThat(Slugs.variant("reelrr", 20)).isEqualTo("reelrr-20");
        assertThat(Slugs.variant("reelrr", 21)).matches("reelrr-[a-z0-9]{6}");

        String longBase = "a".repeat(50);
        assertThat(Slugs.variant(longBase, 2)).hasSize(50).endsWith("-2");
        assertThat(Slugs.variant(longBase, 21)).hasSize(50);
        for (int attempt = 1; attempt <= 25; attempt++) {
            assertThat(Slugs.isValid(Slugs.variant(longBase, attempt))).isTrue();
        }
    }
}
