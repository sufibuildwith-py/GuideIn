package io.guidein.audit.application;

import io.guidein.audit.api.AuditCommand;
import io.guidein.audit.api.AuditEvent;
import io.guidein.audit.api.AuditLedger;
import io.guidein.audit.domain.AuditHashes;
import io.guidein.observability.api.KernelMetrics;
import io.guidein.platform.api.CanonicalJson;
import io.guidein.platform.api.Digests;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class JdbcAuditLedger implements AuditLedger {
    private final JdbcClient jdbc;
    private final CanonicalJson canonicalizer;
    private final KernelMetrics metrics;

    JdbcAuditLedger(JdbcClient jdbc, CanonicalJson canonicalizer, KernelMetrics metrics) {
        this.jdbc = jdbc;
        this.canonicalizer = canonicalizer;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public AuditEvent append(AuditCommand command) {
        jdbc.sql("INSERT INTO audit_heads(tenant_id) VALUES (:tenantId) ON CONFLICT DO NOTHING")
                .param("tenantId", command.tenantId()).update();
        Head head = jdbc.sql("SELECT last_sequence, last_hash FROM audit_heads WHERE tenant_id=:tenantId FOR UPDATE")
                .param("tenantId", command.tenantId())
                .query((rs, row) -> new Head(rs.getLong(1), rs.getBytes(2))).single();
        long sequence = head.sequence() + 1;
        byte[] canonicalPayload = canonicalizer.canonicalize(command.payload());
        byte[] payloadHash = Digests.sha256(canonicalPayload);
        byte[] eventHash = AuditHashes.eventHash(command, sequence, payloadHash, head.hash());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit_events(id, tenant_id, sequence, actor_type, actor_id, action, resource_type,
                    resource_id, correlation_id, occurred_at, canonical_payload, canonical_payload_hash,
                    previous_hash, event_hash)
                VALUES (:id, :tenantId, :sequence, :actorType, :actorId, :action, :resourceType,
                    :resourceId, :correlationId, :occurredAt, CAST(:payload AS jsonb), :payloadHash,
                    :previousHash, :eventHash)
                """).param("id", id).param("tenantId", command.tenantId()).param("sequence", sequence)
                .param("actorType", command.actorType().name()).param("actorId", command.actorId())
                .param("action", command.action()).param("resourceType", command.resourceType())
                .param("resourceId", command.resourceId()).param("correlationId", command.correlationId())
                .param("occurredAt", Timestamp.from(command.occurredAt()))
                .param("payload", new String(canonicalPayload, StandardCharsets.UTF_8))
                .param("payloadHash", payloadHash).param("previousHash", head.hash()).param("eventHash", eventHash)
                .update();
        jdbc.sql("UPDATE audit_heads SET last_sequence=:sequence, last_hash=:hash WHERE tenant_id=:tenantId")
                .param("sequence", sequence).param("hash", eventHash).param("tenantId", command.tenantId()).update();
        metrics.auditAppended();
        return new AuditEvent(id, command.tenantId(), sequence, command.occurredAt(), payloadHash, head.hash(), eventHash);
    }

    @Override
    @Transactional(readOnly = true)
    public IntegrityResult verify(UUID tenantId) {
        // One statement gives head and rows the same snapshot even inside a caller's READ COMMITTED transaction.
        List<SnapshotRow> snapshot = jdbc.sql("""
                SELECT head.last_sequence, head.last_hash, event.sequence, event.actor_type, event.actor_id,
                       event.action, event.resource_type, event.resource_id, event.correlation_id,
                       event.occurred_at, event.canonical_payload::text, event.canonical_payload_hash,
                       event.previous_hash, event.event_hash
                  FROM (SELECT * FROM audit_events WHERE tenant_id=:tenantId) event
                  FULL JOIN (SELECT tenant_id, last_sequence, last_hash FROM audit_heads WHERE tenant_id=:tenantId) head
                    ON event.tenant_id=head.tenant_id
                 ORDER BY event.sequence
                """).param("tenantId", tenantId)
                .query((rs, row) -> new SnapshotRow(new Head(rs.getLong(1), rs.getBytes(2)),
                        rs.getObject(3) == null ? null : new StoredEvent(rs.getLong(3), AuditCommand.ActorType.valueOf(rs.getString(4)),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class),
                        rs.getObject(9, UUID.class), rs.getTimestamp(10).toInstant(), rs.getString(11), rs.getBytes(12),
                        rs.getBytes(13), rs.getBytes(14)))).list();
        List<StoredEvent> events = snapshot.stream().map(SnapshotRow::event).filter(java.util.Objects::nonNull).toList();
        byte[] previous = null;
        long expectedSequence = 1;
        for (StoredEvent event : events) {
            byte[] canonical = canonicalizer.canonicalizeJson(event.payload());
            byte[] payloadHash = Digests.sha256(canonical);
            AuditCommand command = new AuditCommand(tenantId, event.actorType(), event.actorId(), event.action(),
                    event.resourceType(), event.resourceId(), event.correlationId(), event.occurredAt(), java.util.Map.of());
            byte[] expectedHash = AuditHashes.eventHash(command, event.sequence(), payloadHash, previous);
            if (event.sequence() != expectedSequence || !Arrays.equals(payloadHash, event.payloadHash())
                    || !Arrays.equals(previous, event.previousHash()) || !Arrays.equals(expectedHash, event.eventHash())) {
                metrics.auditIntegrityFailure();
                return new IntegrityResult(false, expectedSequence - 1, event.sequence());
            }
            previous = event.eventHash();
            expectedSequence++;
        }
        Head head = snapshot.isEmpty() ? new Head(0, null) : snapshot.getFirst().head();
        if (head.sequence() != expectedSequence - 1 || !Arrays.equals(head.hash(), previous)) {
            metrics.auditIntegrityFailure();
            return new IntegrityResult(false, events.size(), expectedSequence);
        }
        return new IntegrityResult(true, events.size(), null);
    }

    private record Head(long sequence, byte[] hash) {}
    private record SnapshotRow(Head head, StoredEvent event) {}
    private record StoredEvent(long sequence, AuditCommand.ActorType actorType, UUID actorId, String action,
                               String resourceType, UUID resourceId, UUID correlationId, Instant occurredAt,
                               String payload, byte[] payloadHash, byte[] previousHash, byte[] eventHash) {}
}
