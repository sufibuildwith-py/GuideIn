package io.guidein.jobs.application;

import io.guidein.jobs.api.ClaimedJob;
import io.guidein.jobs.api.FailureCategory;
import io.guidein.jobs.api.JobCommand;
import io.guidein.jobs.api.JobQueue;
import io.guidein.jobs.api.JobStatus;
import io.guidein.observability.api.KernelMetrics;
import io.guidein.platform.api.ErrorCode;
import io.guidein.platform.api.GuideInException;
import io.guidein.platform.api.CanonicalJson;
import io.guidein.platform.api.TenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class PostgresJobQueue implements JobQueue {
    private final JdbcClient jdbc;
    private final TenantContext context;
    private final CanonicalJson canonicalizer;
    private final KernelMetrics metrics;
    private final Duration leaseDuration;

    PostgresJobQueue(JdbcClient jdbc, TenantContext context, CanonicalJson canonicalizer,
                     KernelMetrics metrics, @Value("${guidein.jobs.lease-duration:30s}") Duration leaseDuration) {
        this.jdbc = jdbc;
        this.context = context;
        this.canonicalizer = canonicalizer;
        this.metrics = metrics;
        this.leaseDuration = leaseDuration;
    }

    @Override
    @Transactional
    public UUID enqueue(JobCommand command) {
        context.setTenant(command.tenantId());
        UUID id = UUID.randomUUID();
        Optional<UUID> inserted = jdbc.sql("""
                    INSERT INTO job_queue(id, tenant_id, job_type, dedupe_key, payload_json, status,
                        available_at, max_attempts, correlation_id, causation_id, created_at)
                    VALUES (:id, :tenantId, :jobType, :dedupeKey, CAST(:payload AS jsonb), 'READY',
                        :availableAt, :maxAttempts, :correlationId, :causationId, clock_timestamp())
                    ON CONFLICT (tenant_id, job_type, dedupe_key)
                      WHERE status IN ('READY','RUNNING')
                    DO NOTHING
                    RETURNING id
                    """).param("id", id).param("tenantId", command.tenantId())
                    .param("jobType", command.jobType()).param("dedupeKey", command.dedupeKey())
                    .param("payload", new String(canonicalizer.canonicalize(command.payload()), StandardCharsets.UTF_8))
                    .param("availableAt", Timestamp.from(command.availableAt())).param("maxAttempts", command.maxAttempts())
                    .param("correlationId", command.correlationId()).param("causationId", command.causationId())
                    .query(UUID.class).optional();
        if (inserted.isPresent()) {
            metrics.jobEnqueued();
            return inserted.get();
        }
        return jdbc.sql("""
                    SELECT id FROM job_queue WHERE tenant_id=:tenantId AND job_type=:jobType
                      AND dedupe_key=:dedupeKey AND status IN ('READY','RUNNING')
                    """).param("tenantId", command.tenantId()).param("jobType", command.jobType())
                    .param("dedupeKey", command.dedupeKey()).query(UUID.class).single();
    }

    @Override
    @Transactional
    public Optional<ClaimedJob> claimNext(UUID tenantId) {
        context.setTenant(tenantId);
        UUID token = UUID.randomUUID();
        Optional<ClaimedJob> result = jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM job_queue
                     WHERE tenant_id=:tenantId AND attempt_count < max_attempts
                       AND ((status='READY' AND available_at <= clock_timestamp())
                         OR (status='RUNNING' AND lease_until <= clock_timestamp()))
                     ORDER BY available_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE job_queue job
                   SET status='RUNNING', lease_token=:token,
                       lease_until=clock_timestamp() + make_interval(secs => :leaseSeconds),
                       attempt_count=attempt_count + 1, completed_at=NULL
                  FROM candidate WHERE job.id=candidate.id
                RETURNING job.id, job.tenant_id, job.job_type, job.dedupe_key, job.payload_json::text,
                          job.attempt_count, job.max_attempts, job.lease_token, job.lease_until,
                          job.correlation_id, job.causation_id
                """).param("tenantId", tenantId).param("token", token)
                .param("leaseSeconds", Math.toIntExact(leaseDuration.toSeconds()))
                .query((rs, row) -> new ClaimedJob(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7),
                        rs.getObject(8, UUID.class), rs.getTimestamp(9).toInstant(), rs.getObject(10, UUID.class),
                        rs.getObject(11, UUID.class))).optional();
        result.ifPresent(ignored -> metrics.jobClaimed());
        return result;
    }

    @Override
    @Transactional
    public boolean complete(UUID tenantId, UUID jobId, UUID leaseToken) {
        context.setTenant(tenantId);
        boolean completed = jdbc.sql("""
                UPDATE job_queue SET status='SUCCEEDED', completed_at=clock_timestamp(),
                    lease_token=NULL, lease_until=NULL
                 WHERE id=:id AND status='RUNNING' AND lease_token=:token AND lease_until > clock_timestamp()
                """).param("id", jobId).param("token", leaseToken).update() == 1;
        if (completed) metrics.jobTerminal();
        return completed;
    }

    @Override
    @Transactional
    public JobStatus fail(UUID tenantId, UUID jobId, UUID leaseToken, FailureCategory category, String errorCode) {
        context.setTenant(tenantId);
        CurrentJob current = jdbc.sql("""
                SELECT attempt_count, max_attempts FROM job_queue
                 WHERE id=:id AND status='RUNNING' AND lease_token=:token AND lease_until > clock_timestamp()
                 FOR UPDATE
                """).param("id", jobId).param("token", leaseToken)
                .query((rs, row) -> new CurrentJob(rs.getInt(1), rs.getInt(2))).optional()
                .orElseThrow(() -> new GuideInException(ErrorCode.CONFLICT, "The job lease is no longer current."));
        if (category.retryable() && current.attempts() < current.maxAttempts()) {
            int delaySeconds = backoffSeconds(current.attempts());
            jdbc.sql("""
                    UPDATE job_queue SET status='READY', available_at=clock_timestamp()+make_interval(secs => :delay),
                        lease_token=NULL, lease_until=NULL, last_error_code=:errorCode WHERE id=:id
                    """).param("delay", delaySeconds).param("errorCode", safeCode(errorCode)).param("id", jobId).update();
            metrics.jobRetried();
            return JobStatus.READY;
        }
        JobStatus terminal = category.retryable() ? JobStatus.DEAD : JobStatus.FAILED;
        jdbc.sql("""
                UPDATE job_queue SET status=:status, completed_at=clock_timestamp(), lease_token=NULL,
                    lease_until=NULL, last_error_code=:errorCode WHERE id=:id
                """).param("status", terminal.name()).param("errorCode", safeCode(errorCode)).param("id", jobId).update();
        metrics.jobTerminal();
        if (terminal == JobStatus.DEAD) metrics.jobDead();
        return terminal;
    }

    private int backoffSeconds(int attempt) {
        return switch (attempt) { case 1 -> 5; case 2 -> 30; case 3 -> 120; default -> 600; };
    }

    private String safeCode(String code) {
        if (code == null || !code.matches("[A-Z0-9_]{1,80}")) return "INTERNAL";
        return code;
    }

    private record CurrentJob(int attempts, int maxAttempts) {}
}
