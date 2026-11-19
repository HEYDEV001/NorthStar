package com.northstar.integrations.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the worker's {@link CredentialStore} (step 2.1b, integration-specs.md section 7), and only on the worker: with
 * {@code NORTHSTAR_WORKER_ENABLED} off (every API container) none of this exists, so the API holds neither the keys nor
 * a login that can read the secrets schema.
 *
 * <p>On the worker every setting is required, and it fails at startup, before any connection, naming the missing or
 * malformed setting and never its value: the keys, the active key ID, a database URL without a password in it, and
 * secrets_user's login. The pool logs in as secrets_user through the same pooler as the app (transaction pooler,
 * {@code prepareThreshold=0}; the store keeps no session state). It is not a bean: only the store holds it, and closes
 * it when Spring closes the store. The keyring and cipher are not beans either.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("northstar.worker.enabled")
class CredentialsConfiguration {

    private static final Pattern PERCENT_ESCAPE = Pattern.compile("%([0-9a-fA-F]{2})");

    @Bean
    CredentialStore credentialStore(
            @Value("${northstar.credentials.keys}") String keys,
            @Value("${northstar.credentials.active-key}") String activeKey,
            @Value("${northstar.credentials.database.url}") String url,
            @Value("${northstar.credentials.database.username}") String username,
            @Value("${northstar.credentials.database.password}") String password,
            @Value("${northstar.credentials.database.pool-size}") int poolSize) {
        CredentialKeyring keyring = CredentialKeyring.parse(keys, activeKey);
        if (url.isBlank()) throw new IllegalStateException("DB_URL is required when the worker is on");
        if (carriesPassword(url)) {
            throw new IllegalStateException(
                    "The credentials database URL must not carry a password; set SECRETS_DB_PASSWORD");
        }
        if (username.isBlank()) throw new IllegalStateException("SECRETS_DB_USER is required when the worker is on");
        if (password.isBlank()) {
            throw new IllegalStateException("SECRETS_DB_PASSWORD is required when the worker is on");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("credentials");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.addDataSourceProperty("prepareThreshold", 0);
        HikariDataSource pool = new HikariDataSource(config);
        return new CredentialStore(pool, new CredentialCipher(keyring));
    }

    /**
     * A password parameter, or user:password@ before the host: the same rule as the app's and JobRunr's pools and the
     * migration runner (step 1.15). Passwords go in their own setting, never in a URL that logs or error messages may
     * print.
     */
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
