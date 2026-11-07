package com.northstar;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Signs test access tokens shaped like Supabase's (ES256, same claims) and serves the matching JWKS from a local HTTP
 * server, so tests run the app's real token verification without a Supabase instance. Each builder method breaks one
 * thing at a time.
 */
public final class TestTokens implements AutoCloseable {

    private static final String KEY_ID = "test-key";

    private final ECKey signingKey;
    private final ECKey impostorKey;
    private final HttpServer jwksServer;
    private final String issuer;

    TestTokens() throws JOSEException, IOException {
        signingKey = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();
        impostorKey = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate(); // same kid, different key
        byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

        jwksServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        jwksServer.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        jwksServer.start();
        issuer = "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/auth/v1";
    }

    public String issuer() {
        return issuer;
    }

    public String jwkSetUri() {
        return issuer + "/.well-known/jwks.json";
    }

    /** A valid token for the user, as Supabase would issue it after login. */
    public String forUser(UUID userId) {
        return token(userId).sign();
    }

    public Token token(UUID userId) {
        return new Token(userId);
    }

    @Override
    public void close() {
        jwksServer.stop(0);
    }

    /** A token under construction: valid by default; each method changes exactly one thing. */
    public final class Token {

        private final Map<String, Object> claims = new LinkedHashMap<>();
        private Instant issuedAt = Instant.now();
        private Instant expiresAt = issuedAt.plus(Duration.ofHours(1));
        private ECKey key = signingKey;
        private boolean hs256;

        private Token(UUID userId) {
            claims.put("iss", issuer);
            claims.put("aud", "authenticated");
            claims.put("sub", userId.toString());
            claims.put("role", "authenticated");
            claims.put("is_anonymous", false);
            claims.put("aal", "aal1");
            claims.put("email", "test@example.com");
        }

        public Token expired() {
            issuedAt = Instant.now().minus(Duration.ofHours(2));
            expiresAt = Instant.now().minus(Duration.ofHours(1));
            return this;
        }

        public Token claim(String name, Object value) {
            claims.put(name, value);
            return this;
        }

        public Token without(String claim) {
            claims.remove(claim);
            return this;
        }

        public Token signedWithAnotherKey() {
            key = impostorKey;
            return this;
        }

        /** Signed with a shared secret instead of the published key: must never be accepted. */
        public Token signedWithHs256() {
            hs256 = true;
            return this;
        }

        public String sign() {
            JWTClaimsSet.Builder set =
                    new JWTClaimsSet.Builder().issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt));
            claims.forEach(set::claim);
            try {
                JWSAlgorithm algorithm = hs256 ? JWSAlgorithm.HS256 : JWSAlgorithm.ES256;
                JWSHeader header =
                        new JWSHeader.Builder(algorithm).keyID(KEY_ID).build();
                SignedJWT jwt = new SignedJWT(header, set.build());
                jwt.sign(signer());
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }

        private JWSSigner signer() throws JOSEException {
            if (!hs256) return new ECDSASigner(key);
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            return new MACSigner(secret);
        }
    }
}
