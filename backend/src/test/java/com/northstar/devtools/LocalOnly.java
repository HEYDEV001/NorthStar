package com.northstar.devtools;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses any address that is not this machine (step 2.11): the gate tools write to the database in .env.local, and
 * only a local one may be written to. The rules are strict on purpose, because a URL can point elsewhere in more ways
 * than its host: one host only (Postgres tries a list in turn), spelled as a loopback address, no user or password in
 * the address, and no option but the harmless few, since the driver lets options such as PGHOST or socketFactory
 * replace where it connects. A refusal names the setting, never its value.
 *
 * <p>This cannot see through a tunnel: an SSH forward that makes another database answer on 127.0.0.1 looks local.
 */
final class LocalOnly {

    private static final Pattern DATABASE = Pattern.compile(
            "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]{1,5})?/[A-Za-z0-9_]{1,63}(?:\\?(.*))?");

    /** The driver's options that cannot change where it connects, or what it reads to find out. */
    private static final Set<String> HARMLESS_OPTIONS = Set.of("sslmode", "prepareThreshold", "ApplicationName");

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private LocalOnly() {}

    /** The database setting must be {@code jdbc:postgresql://<loopback>[:port]/<database>}. */
    static void database(String setting, String url) {
        Matcher match = DATABASE.matcher(url == null ? "" : url);
        if (!match.matches()) {
            throw new LocalToolException(
                    "database_not_local",
                    setting + " in .env.local must be jdbc:postgresql://127.0.0.1:<port>/<database> (or localhost, or"
                            + " [::1]): one local host, with no user or password in it. Nothing was done.");
        }
        String options = match.group(1);
        if (options == null || options.isEmpty()) return;
        for (String option : options.split("&", -1)) {
            String name = option.contains("=") ? option.substring(0, option.indexOf('=')) : option;
            if (!HARMLESS_OPTIONS.contains(name)) {
                throw new LocalToolException(
                        "database_not_local",
                        setting + " in .env.local carries an option other than " + String.join(", ", sorted())
                                + ", which can change where it connects. Nothing was done.");
            }
        }
    }

    /** The address of a local service, such as Supabase: http or https on a loopback host, with no login in it. */
    static void service(String setting, String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url);
        } catch (IllegalArgumentException malformed) {
            throw notLocal(setting);
        }
        boolean web = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        boolean bare = uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        if (!web || !bare || !isLoopback(uri.getHost())) throw notLocal(setting);
    }

    /** True for localhost, 127.0.0.1 and ::1, spelled exactly so: a name that only contains one of them is not. */
    static boolean isLoopback(String host) {
        return host != null && LOOPBACK.contains(host);
    }

    private static LocalToolException notLocal(String setting) {
        return new LocalToolException(
                "service_not_local",
                setting + " in .env.local must be http://127.0.0.1:<port> (or localhost, or [::1]). Nothing was done.");
    }

    private static List<String> sorted() {
        return HARMLESS_OPTIONS.stream().sorted().toList();
    }
}
