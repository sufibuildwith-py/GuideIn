package io.guidein.integration;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** A real loopback JWK endpoint and RSA signer; never replaces the production decoder. */
final class LocalOidc {
    private final RSAKey key;
    private final HttpServer server;
    LocalOidc() {
        try {
            key = new RSAKeyGenerator(2048).keyID("proof-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String token(String subject, String issuer, String audience, Instant expiration) throws Exception {
        return token(subject, issuer, audience, expiration, "SECRET_EMAIL_CANARY@example.test", key);
    }
    String tokenWithEmail(String subject, String email) throws Exception {
        return token(subject, issuer(), "guidein-api", Instant.now().plusSeconds(600), email, key);
    }
    String invalidSignature(String subject) throws Exception {
        return token(subject, issuer(), "guidein-api", Instant.now().plusSeconds(600), "proof@example.test", new RSAKeyGenerator(2048).generate());
    }
    private String token(String subject, String issuer, String audience, Instant expiration, String email, RSAKey signingKey) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
                        .issueTime(Date.from(Instant.now().minusSeconds(600)))
                        .expirationTime(Date.from(expiration)).claim("email", email).build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
    void close() { server.stop(0); }
}
