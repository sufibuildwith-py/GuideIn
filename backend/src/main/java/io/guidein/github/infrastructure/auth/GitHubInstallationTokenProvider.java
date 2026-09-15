package io.guidein.github.infrastructure.auth;

import java.time.Instant;
import java.util.Map;

public interface GitHubInstallationTokenProvider {
    InstallationToken tokenFor(long installationId, long generation);
    void evict(long installationId);

    final class InstallationToken {
        private final String secret;
        private final Instant expiresAt;
        private final long installationId;
        private final Map<String,String> permissions;
        public InstallationToken(String secret, Instant expiresAt, long installationId, Map<String,String> permissions) {
            if (secret == null || secret.isEmpty() || expiresAt == null) throw new IllegalArgumentException("Missing installation credential");
            this.secret=secret; this.expiresAt=expiresAt; this.installationId=installationId;
            this.permissions=Map.copyOf(permissions);
        }
        public String secret() { return secret; }
        public Instant expiresAt() { return expiresAt; }
        public long installationId() { return installationId; }
        public Map<String,String> permissions() { return permissions; }
        @Override public String toString() { return "InstallationToken[redacted]"; }
    }
}
