package io.guidein.github.infrastructure.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Development secret-file adapter. Accepts PKCS#8 RSA; never logs key material or token. */
public final class PemGitHubAppJwtSigner implements GitHubAppJwtSigner {
    private final PrivateKey key;
    private final String clientId;
    private final Clock clock;

    public PemGitHubAppJwtSigner(Path path, String clientId, Clock clock) {
        this.clientId = clientId;
        this.clock = clock;
        try {
            if (Files.size(path) > 32_768) throw new IllegalArgumentException();
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")) throw new IllegalArgumentException();
            byte[] der = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", ""));
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            java.util.Arrays.fill(der, (byte) 0);
            if (((java.security.interfaces.RSAKey) key).getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException();
        } catch (Exception ignored) {
            throw new IllegalArgumentException("GitHub signing configuration is invalid");
        }
    }

    @Override public String createJwt() {
        try {
            var mapper = JsonMapper.builder().build();
            long now = clock.instant().getEpochSecond();
            String unsigned = encode(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT"))) + "."
                    + encode(mapper.writeValueAsBytes(Map.of("iss", clientId, "iat", now - 60, "exp", now + 540)));
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + encode(signer.sign());
        } catch (Exception ignored) { throw new IllegalStateException("GitHub signing failed"); }
    }

    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
