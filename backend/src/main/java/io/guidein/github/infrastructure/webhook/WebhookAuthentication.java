package io.guidein.github.infrastructure.webhook;

import io.guidein.github.infrastructure.auth.GitHubWebhookSecretProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookAuthentication {
    private final GitHubWebhookSecretProvider secrets;
    public WebhookAuthentication(GitHubWebhookSecretProvider secrets) { this.secrets = secrets; }

    /** No decoding, parsing, normalization or whitespace transformation occurs here. */
    public String verify(byte[] raw, String signature) {
        if (signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}")) throw new InvalidSignature();
        byte[] actual = HexFormat.of().parseHex(signature.substring(7));
        var keys = secrets.activeVerificationSecrets();
        if (keys.isEmpty() || keys.size() > 2) throw new IllegalStateException("Invalid verification key count");
        String matched = null;
        for (var key : keys) {
            byte[] secret = key.value();
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                if (MessageDigest.isEqual(actual, mac.doFinal(raw))) matched = key.version();
            } catch (java.security.GeneralSecurityException ignored) {
                throw new IllegalStateException("Webhook verification unavailable");
            } finally { java.util.Arrays.fill(secret, (byte) 0); }
        }
        if (matched == null) throw new InvalidSignature();
        return matched;
    }

    public static byte[] readBounded(InputStream stream, int limit) throws IOException {
        if (limit < 1 || limit > 26_214_400) throw new IllegalArgumentException("Invalid payload limit");
        var output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = stream.read(buffer, 0, Math.min(buffer.length, limit - total + 1))) != -1;) {
            total += read;
            if (total > limit) throw new PayloadTooLarge();
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    public static final class InvalidSignature extends RuntimeException {
        public InvalidSignature() { super("Invalid webhook signature"); }
    }
    public static final class PayloadTooLarge extends IOException {
        public PayloadTooLarge() { super("Webhook payload exceeds limit"); }
    }
}
