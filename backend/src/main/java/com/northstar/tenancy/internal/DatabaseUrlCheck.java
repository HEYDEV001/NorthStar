package com.northstar.tenancy.internal;

import java.util.Locale;
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
        String lower = url.toLowerCase(Locale.ROOT);
        int authorityStart = lower.indexOf("//");
        int authorityEnd = authorityStart < 0 ? -1 : lower.indexOf('/', authorityStart + 2);
        String authority = authorityStart < 0
                ? ""
                : lower.substring(authorityStart + 2, authorityEnd < 0 ? lower.length() : authorityEnd);
        return lower.contains("password") || authority.contains("@");
    }
}
