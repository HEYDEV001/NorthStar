package com.northstar.tenancy.internal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The app's database URL never carries a password (step 1.15): startup logs print the URL (Hibernate's "Database JDBC
 * URL" line), so the password belongs in DB_PASSWORD. Checked before any bean is created, and the refusal never repeats
 * the URL.
 */
@Configuration(proxyBeanMethods = false)
class DatabaseUrlCheck {

    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%([0-9a-fA-F]{2})");

    @Bean
    static BeanFactoryPostProcessor databaseUrlCarriesNoPassword(Environment environment) {
        return beanFactory -> {
            if (carriesPassword(environment.getProperty("spring.datasource.url", ""))) {
                throw new IllegalStateException("DB_URL must not carry a password; set DB_PASSWORD instead");
            }
        };
    }

    /** A password parameter, or user:password@ before the host. */
    static boolean carriesPassword(String url) {
        String lower = withoutPercentEscapes(url).toLowerCase(Locale.ROOT);
        int authorityStart = lower.indexOf("//");
        int authorityEnd = authorityStart < 0 ? -1 : lower.indexOf('/', authorityStart + 2);
        String authority = authorityStart < 0
                ? ""
                : lower.substring(authorityStart + 2, authorityEnd < 0 ? lower.length() : authorityEnd);
        return lower.contains("password") || authority.contains("@");
    }

    /**
     * The URL with %XX escapes undone, repeatedly (so %2577 counts as w). The driver decodes parameter values but not
     * names, so {@code pass%77ord=...} is no password to it, yet the secret would still sit in a URL that logs print.
     * The same rule is in every copy of this check (DatabaseUrlCheck, JobsConfiguration, CredentialsConfiguration and
     * the migration runner); UrlPasswordChecksTests runs the same cases against all of them.
     */
    private static String withoutPercentEscapes(String url) {
        String current = url;
        for (int pass = 0; pass < 5; pass++) {
            String decoded = PERCENT_ESCAPE
                    .matcher(current)
                    .replaceAll(escape ->
                            Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(escape.group(1), 16))));
            if (decoded.equals(current)) return decoded;
            current = decoded;
        }
        return current;
    }
}
