package com.northstar.tenancy.internal;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Business slugs, which appear in chat links and URLs (step 1.7). The rules live here only: the format (also enforced by
 * the tenants_slug_format check), the reserved words, and how a slug is generated from a business name.
 */
final class Slugs {

    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 50;

    /** Lowercase letters and digits, with single hyphens between them. Length is checked separately. */
    static final String PATTERN = "[a-z0-9]+(-[a-z0-9]+)*";

    /** Slugs no business may have: they would clash with Northstar's own routes and hostnames. */
    static final Set<String> RESERVED = Set.of(
            "account",
            "admin",
            "api",
            "app",
            "assets",
            "billing",
            "c",
            "dashboard",
            "docs",
            "help",
            "login",
            "mail",
            "northstar",
            "settings",
            "signup",
            "static",
            "status",
            "support",
            "team",
            "www");

    /** Numbered variants tried when a generated slug is taken, before falling back to a random suffix. */
    static final int MAX_NUMBERED_VARIANT = 20;

    private static final Pattern FORMAT = Pattern.compile(PATTERN);
    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Slugs() {}

    static boolean isValid(String slug) {
        return slug != null
                && slug.length() >= MIN_LENGTH
                && slug.length() <= MAX_LENGTH
                && FORMAT.matcher(slug).matches();
    }

    static boolean isReserved(String slug) {
        return RESERVED.contains(slug);
    }

    /**
     * A slug from a business name: accents stripped, lowercased, every run of anything else turned into one hyphen.
     * A name that leaves too little (for example one written only in Devanagari) gets "business-" and random
     * characters instead.
     */
    static String fromName(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = trimHyphens(ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        slug = trimHyphens(slug.length() > MAX_LENGTH ? slug.substring(0, MAX_LENGTH) : slug);
        return slug.length() >= MIN_LENGTH ? slug : "business-" + random(6);
    }

    /** The n-th candidate for a generated slug: the base, then base-2 … base-20, then base plus a random suffix. */
    static String variant(String base, int attempt) {
        if (attempt == 1) return base;
        String suffix = attempt <= MAX_NUMBERED_VARIANT ? "-" + attempt : "-" + random(6);
        String trimmed = base.length() + suffix.length() > MAX_LENGTH
                ? trimHyphens(base.substring(0, MAX_LENGTH - suffix.length()))
                : base;
        return trimmed + suffix;
    }

    private static String trimHyphens(String value) {
        return value.replaceAll("^-+|-+$", "");
    }

    private static String random(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(RANDOM_ALPHABET.charAt(RANDOM.nextInt(RANDOM_ALPHABET.length())));
        return value.toString();
    }
}
