package io.guidein.events.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxCommand(UUID tenantId, String aggregateType, UUID aggregateId, String eventType,
                            int eventVersion, Map<String, Object> payload, UUID correlationId,
                            UUID causationId, Instant occurredAt) {
    public OutboxCommand {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        if (eventVersion < 1) throw new IllegalArgumentException("eventVersion must be positive");
    }
}

