package io.guidein.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(UUID id, UUID tenantId, long sequence, Instant occurredAt,
                         byte[] payloadHash, byte[] previousHash, byte[] eventHash) {}

