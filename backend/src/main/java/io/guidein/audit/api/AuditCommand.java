package io.guidein.audit.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

public record AuditCommand(UUID tenantId, ActorType actorType, UUID actorId, String action,
                           String resourceType, UUID resourceId, UUID correlationId,
                           Instant occurredAt, Map<String, Object> payload) {
    public enum ActorType { USER, SERVICE, SYSTEM }

    public AuditCommand {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        occurredAt = (occurredAt == null ? Instant.now() : occurredAt).truncatedTo(ChronoUnit.MICROS);
    }
}
