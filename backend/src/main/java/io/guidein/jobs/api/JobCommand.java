package io.guidein.jobs.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record JobCommand(UUID tenantId, String jobType, String dedupeKey, Map<String, Object> payload,
                         Instant availableAt, int maxAttempts, UUID correlationId, UUID causationId) {
    public JobCommand {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        availableAt = availableAt == null ? Instant.now() : availableAt;
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
    }
}

