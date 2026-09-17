package io.guidein.github.api;

import io.guidein.jobs.api.FailureCategory;
import java.time.Instant;
import java.util.*;

/** Provider-neutral source bytes; GitHub transport DTOs and credentials never cross this boundary. */
public interface RepositoryMaterialSource {
    record Bounds(int files, long totalBytes, int fileBytes, int seconds, Set<String> excludedDirectories) {
        public Bounds {
            excludedDirectories = Set.copyOf(excludedDirectories);
            if (files < 1 || files > 100_000 || totalBytes < 1 || totalBytes > 256L * 1024 * 1024
                    || fileBytes < 1 || fileBytes > 2 * 1024 * 1024 || seconds < 1 || seconds > 300) throw new IllegalArgumentException("Invalid source bounds");
        }
    }
    record Gap(String category, String path) {}
    record Material(String sourceSha, Map<String, byte[]> files, List<Gap> gaps) {
        public Material { files = copy(files); gaps = List.copyOf(gaps); }
        @Override public Map<String, byte[]> files() { return copy(files); }
        private static Map<String, byte[]> copy(Map<String, byte[]> source) {
            var result = new TreeMap<String, byte[]>(); source.forEach((key, value) -> result.put(key, value.clone())); return Collections.unmodifiableMap(result);
        }
    }
    final class Failure extends RuntimeException {
        private final FailureCategory category; private final Instant retryAt;
        public Failure(FailureCategory category, Instant retryAt) { super("SOURCE_" + category.name()); this.category = category; this.retryAt = retryAt; }
        public FailureCategory category() { return category; }
        public Instant retryAt() { return retryAt; }
    }
    Material fetch(UUID tenantId, UUID repositoryId, String sourceSha, Bounds bounds, Runnable checkpoint);
    void requireAuthority(UUID tenantId, UUID repositoryId);
    List<UUID> routedTenants();
}
