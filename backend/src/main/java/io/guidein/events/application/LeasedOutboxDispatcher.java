package io.guidein.events.application;

import io.guidein.events.api.OutboxConsumer;
import io.guidein.events.api.OutboxDispatcher;
import io.guidein.events.api.OutboxEvent;
import io.guidein.observability.api.KernelMetrics;
import io.guidein.platform.api.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class LeasedOutboxDispatcher implements OutboxDispatcher {
    private final JdbcClient jdbc;
    private final TenantContext context;
    private final KernelMetrics metrics;
    private final Duration leaseDuration;

    LeasedOutboxDispatcher(JdbcClient jdbc, TenantContext context, KernelMetrics metrics,
                           @Value("${guidein.outbox.lease-duration:30s}") Duration leaseDuration) {
        this.jdbc = jdbc;
        this.context = context;
        this.metrics = metrics;
        this.leaseDuration = leaseDuration;
    }

    @Override
    @Transactional
    public boolean dispatchNext(UUID tenantId, OutboxConsumer consumer) {
        return dispatchNext(tenantId, null, consumer);
    }

    @Override
    @Transactional
    public boolean dispatchNext(UUID tenantId, String eventType, OutboxConsumer consumer) {
        context.setTenant(tenantId);
        UUID token = UUID.randomUUID();
        Optional<OutboxEvent> claimed = jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM outbox_events
                     WHERE tenant_id=:tenantId AND published_at IS NULL
                       AND (CAST(:eventType AS text) IS NULL OR event_type=:eventType)
                       AND (locked_until IS NULL OR locked_until <= clock_timestamp())
                     ORDER BY occurred_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE outbox_events event
                   SET lock_token=:token,
                       locked_until=clock_timestamp() + make_interval(secs => :leaseSeconds),
                       attempt_count=attempt_count + 1
                  FROM candidate WHERE event.id=candidate.id
                RETURNING event.id, event.tenant_id, event.aggregate_type, event.aggregate_id,
                          event.event_type, event.event_version, event.payload_json::text, event.payload_hash,
                          event.correlation_id, event.causation_id, event.occurred_at, event.lock_token
                """).param("tenantId", tenantId).param("token", token).param("eventType", eventType)
                .param("leaseSeconds", Math.toIntExact(leaseDuration.toSeconds()))
                .query((rs, row) -> new OutboxEvent(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5), rs.getInt(6),
                        rs.getString(7), rs.getBytes(8), rs.getObject(9, UUID.class), rs.getObject(10, UUID.class),
                        rs.getTimestamp(11).toInstant(), rs.getObject(12, UUID.class))).optional();
        if (claimed.isEmpty()) return false;
        try {
            consumer.accept(claimed.get());
            int updated = jdbc.sql("""
                    UPDATE outbox_events SET published_at=clock_timestamp(), lock_token=NULL, locked_until=NULL
                     WHERE id=:id AND lock_token=:token AND published_at IS NULL
                    """).param("id", claimed.get().id()).param("token", token).update();
            if (updated != 1) throw new IllegalStateException("Outbox lease was lost");
            metrics.outboxPublished();
            return true;
        } catch (Exception exception) {
            metrics.outboxDispatchFailure();
            jdbc.sql("""
                    UPDATE outbox_events SET lock_token=NULL, locked_until=NULL, last_error_code='DISPATCH_FAILED'
                     WHERE id=:id AND lock_token=:token
                    """).param("id", claimed.get().id()).param("token", token).update();
            return false;
        }
    }
}
