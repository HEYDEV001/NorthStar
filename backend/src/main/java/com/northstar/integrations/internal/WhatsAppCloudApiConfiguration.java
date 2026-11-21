package com.northstar.integrations.internal;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Cloud API sender (step 2.6b, integration-specs.md section 2) and the number reader the sync uses (step
 * 2.8a), on the same base URL, version, timeouts and redirect rule. The Graph API version is pinned in
 * application.properties ({@code northstar.whatsapp.graph.version}, v26.0), never set per environment: the staging app
 * spec gives no component a variable for it, and bumping it is a reviewed change with its tests. Every process builds
 * both, as every process builds the handlers, but only the worker runs them; neither holds a secret, since each
 * business's token is read at call time.
 *
 * <p>Refuses startup, naming the setting and never a value, for a version not shaped like {@code v26.0}, or a base URL
 * that is not plain HTTPS to a host with no path, query or credentials (plain HTTP only to a loopback stand-in).
 */
@Configuration(proxyBeanMethods = false)
class WhatsAppCloudApiConfiguration {

    /** Meta's version names: v, major, a dot and minor (v26.0). */
    static final Pattern VERSION = Pattern.compile("v\\d{1,3}\\.\\d{1,2}");

    /** Connecting gives up after this; the request after REQUEST_TIMEOUT more. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * With the connect timeout, 20 seconds at most: well under the handler's 30, leaving time to store Meta's ID once it
     * answers.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    WhatsAppSender whatsAppSender(
            @Value("${northstar.whatsapp.graph.base-url}") String baseUrl,
            @Value("${northstar.whatsapp.graph.version}") String version) {
        return new CloudApiWhatsAppSender(http(), baseUrl(baseUrl), version(version), REQUEST_TIMEOUT);
    }

    @Bean
    WhatsAppAccountReader whatsAppAccountReader(
            @Value("${northstar.whatsapp.graph.base-url}") String baseUrl,
            @Value("${northstar.whatsapp.graph.version}") String version) {
        return new CloudApiWhatsAppAccountReader(http(), baseUrl(baseUrl), version(version), REQUEST_TIMEOUT);
    }

    /** Never follows a redirect, so the token goes to the configured host only. */
    private static HttpClient http() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String version(String value) {
        if (!VERSION.matcher(value).matches()) {
            throw new IllegalStateException(
                    "northstar.whatsapp.graph.version must be a Graph API version such as v26.0");
        }
        return value;
    }

    private static URI baseUrl(String value) {
        String problem = "northstar.whatsapp.graph.base-url must be https://host with no path, query or credentials";
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(problem);
        }
        boolean bare = uri.getHost() != null
                && uri.getRawUserInfo() == null
                && (uri.getRawPath() == null
                        || uri.getRawPath().isEmpty()
                        || uri.getRawPath().equals("/"))
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        boolean scheme = "https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback(uri.getHost()));
        if (!bare || !scheme) throw new IllegalStateException(problem);
        return uri;
    }

    /** A literal loopback address: a test stand-in. Names are not resolved, so no lookup can make a host local. */
    private static boolean loopback(String host) {
        if (!host.matches("[0-9.]+|\\[[0-9a-fA-F:]+]")) return false;
        try {
            return InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
