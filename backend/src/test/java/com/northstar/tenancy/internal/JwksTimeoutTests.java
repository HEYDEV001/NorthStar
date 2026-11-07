package com.northstar.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Fetching Supabase's signing keys gives up after the configured read timeout instead of holding the request. */
class JwksTimeoutTests {

    private HttpServer stallingKeyServer;
    private String issuer;

    @BeforeEach
    void startAKeyServerThatNeverAnswersInTime() throws Exception {
        stallingKeyServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stallingKeyServer.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        stallingKeyServer.start();
        issuer = "http://127.0.0.1:" + stallingKeyServer.getAddress().getPort() + "/auth/v1";
    }

    @AfterEach
    void stop() {
        stallingKeyServer.stop(0);
    }

    @Test
    void slowKeyServerFailsTheTokenWithinTheReadTimeout() throws Exception {
        JwtDecoder decoder = SecurityConfiguration.decoder(
                issuer,
                issuer + "/.well-known/jwks.json",
                "authenticated",
                Duration.ofSeconds(2),
                Duration.ofMillis(300));

        long started = System.nanoTime();
        assertThatThrownBy(() -> decoder.decode(token())).isInstanceOf(JwtException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(Duration.ofMillis(2_000));
    }

    private String token() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("test-key").generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("test-key").build(),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience("authenticated")
                        .subject(UUID.randomUUID().toString())
                        .claim("role", "authenticated")
                        .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                        .build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}
