package io.guidein.github.infrastructure.auth;

import java.util.List;

public interface GitHubWebhookSecretProvider {
    List<VersionedSecret> activeVerificationSecrets();

    final class VersionedSecret {
        private final String version;
        private final byte[] value;
        public VersionedSecret(String version, byte[] value) {
            if (version == null || !version.matches("[A-Za-z0-9_.-]{1,40}") || value == null || value.length < 16)
                throw new IllegalArgumentException("Invalid webhook secret configuration");
            this.version = version;
            this.value = value.clone();
        }
        public String version() { return version; }
        public byte[] value() { return value.clone(); }
        @Override public String toString() { return "VersionedSecret[redacted]"; }
    }
}
