package io.guidein.events.api;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, UUID tenantId, String aggregateType, UUID aggregateId,
                          String eventType, int eventVersion, String payloadJson, byte[] payloadHash,
                          UUID correlationId, UUID causationId, Instant occurredAt, UUID leaseToken) {}

