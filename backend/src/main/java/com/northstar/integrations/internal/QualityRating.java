package com.northstar.integrations.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A WhatsApp number's quality rating, as stored in whatsapp_accounts.quality_rating (step 2.8a, data-model.md section
 * 9). Read from the phone number node's {@code quality_rating}: Meta's pages show GREEN, YELLOW, RED, NA and UNKNOWN
 * (checked 21 Nov 2026), and publish no full list. NA and UNKNOWN both mean Meta has no rating yet, so both are
 * {@link #UNKNOWN}. Any other value is not stored.
 */
enum QualityRating {
    GREEN("green", 3),
    YELLOW("yellow", 2),
    RED("red", 1),
    UNKNOWN("unknown", 0);

    private final String databaseValue;

    /** Higher is better; 0 is unranked. */
    private final int rank;

    QualityRating(String databaseValue, int rank) {
        this.databaseValue = databaseValue;
        this.rank = rank;
    }

    String databaseValue() {
        return databaseValue;
    }

    /** Meta's value, in any case; empty for a value Meta does not document. */
    static Optional<QualityRating> fromMeta(String value) {
        if (value == null) return Optional.empty();
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "GREEN" -> Optional.of(GREEN);
            case "YELLOW" -> Optional.of(YELLOW);
            case "RED" -> Optional.of(RED);
            case "NA", "UNKNOWN" -> Optional.of(UNKNOWN);
            default -> Optional.empty();
        };
    }

    /** The stored value; empty for null (never read from Meta). */
    static Optional<QualityRating> fromDatabase(String value) {
        return Arrays.stream(values())
                .filter(rating -> rating.databaseValue.equals(value))
                .findFirst();
    }

    /** A drop: from a known rating to a lower one (green to yellow or red, yellow to red). Unknown is never a drop. */
    boolean isDropFrom(QualityRating previous) {
        return previous != null && previous.rank > 0 && rank > 0 && rank < previous.rank;
    }
}
