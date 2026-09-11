package io.guidein.events.application;

import io.guidein.events.api.OutboxCommand;
import io.guidein.events.api.OutboxWriter;
import io.guidein.platform.api.CanonicalJson;
import io.guidein.platform.api.Digests;
import io.guidein.observability.api.KernelMetrics;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
final class JdbcOutboxWriter implements OutboxWriter {
    private final JdbcClient jdbc;
    private final CanonicalJson canonicalizer;
    private final KernelMetrics metrics;

    JdbcOutboxWriter(JdbcClient jdbc, CanonicalJson canonicalizer, KernelMetrics metrics) {
        this.jdbc = jdbc;
        this.canonicalizer = canonicalizer;
        this.metrics = metrics;
    }

    @Override
    public UUID append(OutboxCommand command) {
        byte[] payload = canonicalizer.canonicalize(command.payload());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO outbox_events(id, tenant_id, aggregate_type, aggregate_id, event_type, event_version,
                  payload_json, payload_hash, correlation_id, causation_id, occurred_at)
                VALUES (:id, :tenantId, :aggregateType, :aggregateId, :eventType, :eventVersion,
                  CAST(:payload AS jsonb), :payloadHash, :correlationId, :causationId, :occurredAt)
                """).param("id", id).param("tenantId", command.tenantId())
                .param("aggregateType", command.aggregateType()).param("aggregateId", command.aggregateId())
                .param("eventType", command.eventType()).param("eventVersion", command.eventVersion())
                .param("payload", new String(payload, StandardCharsets.UTF_8))
                .param("payloadHash", Digests.sha256(payload)).param("correlationId", command.correlationId())
                .param("causationId", command.causationId()).param("occurredAt", Timestamp.from(command.occurredAt()))
                .update();
        metrics.outboxAppended();
        return id;
    }
}
