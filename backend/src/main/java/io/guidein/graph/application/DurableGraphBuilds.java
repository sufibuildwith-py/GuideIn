package io.guidein.graph.application;

import io.guidein.graph.api.GraphBuilds;
import io.guidein.graph.api.SystemGraph.Snapshot;
import io.guidein.github.api.RepositoryMaterialSource;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.jobs.api.*;
import io.guidein.platform.api.*;
import io.guidein.tenancy.api.RepositoryQuery;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

public final class DurableGraphBuilds implements GraphBuilds {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DurableGraphBuilds.class);
    private final GraphStore store; private final GraphEngine engine; private final RepositoryMaterialSource source;
    private final JobQueue jobs; private final RepositoryQuery repositories; private final CanonicalJson canonical;
    private final long renewalNanos;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public DurableGraphBuilds(GraphStore store, GraphEngine engine, RepositoryMaterialSource source,
                              JobQueue jobs, RepositoryQuery repositories, CanonicalJson canonical, java.time.Duration leaseDuration) {
        this.store = store; this.engine = engine; this.source = source; this.jobs = jobs; this.repositories = repositories; this.canonical = canonical;
        this.renewalNanos = Math.max(1_000_000L, leaseDuration.toNanos() / 3);
    }
    @Override public Snapshot request(AuthenticatedSubject subject, UUID tenant, UUID repository, String sha, UUID correlation) {
        return store.transaction(tenant, () -> {
            repositories.requireManage(subject, tenant, repository);
            source.requireAuthority(tenant, repository);
            return requestNormalized(tenant, repository, sha, correlation, null);
        });
    }
    @Override public Snapshot requestNormalized(UUID tenant, UUID repository, String sha, UUID correlation, UUID causation) {
        if (sha == null || !sha.matches("[0-9a-f]{40}")) throw new GuideInException(ErrorCode.VALIDATION_FAILED);
        return store.transaction(tenant, () -> {
            String identity = engine.hash(canonical.canonicalize(Map.of("tenant", tenant, "repository", repository, "sha", sha,
                    "builder", engine.builderVersion(), "config", engine.configurationDigest())));
            UUID id = UUID.randomUUID();
            store.jdbc.sql("""
                    INSERT INTO graph_snapshots(id,tenant_id,repository_id,source_sha,input_identity,builder_version,extractor_versions,configuration_digest)
                    VALUES (:id,:tenant,:repository,:sha,:identity,:builder,CAST(:versions AS jsonb),:config)
                    ON CONFLICT (tenant_id,input_identity) DO NOTHING
                    """).param("id", id).param("tenant", tenant).param("repository", repository).param("sha", sha).param("identity", identity)
                    .param("builder", engine.builderVersion()).param("versions", store.json(engine.versions())).param("config", engine.configurationDigest()).update();
            UUID found = store.jdbc.sql("SELECT id FROM graph_snapshots WHERE input_identity=:identity FOR UPDATE").param("identity", identity).query(UUID.class).single();
            Snapshot snapshot = store.snapshot(found);
            if (!Set.of("READY", "PARTIAL").contains(snapshot.status())) {
                UUID existingJob = store.jdbc.sql("SELECT job_id FROM graph_snapshots WHERE id=:id").param("id", found)
                        .query((rs, n) -> rs.getObject(1, UUID.class)).optional().orElse(null);
                if (existingJob != null && jobs.status(tenant, existingJob).filter(s -> s == JobStatus.READY || s == JobStatus.RUNNING).isPresent()) return snapshot;
                UUID job = jobs.enqueue(new JobCommand(tenant, "GRAPH_BUILD", identity, Map.of("snapshot_id", found.toString()), Instant.now(), 5, correlation, causation));
                store.jdbc.sql("UPDATE graph_snapshots SET job_id=:job WHERE id=:id").param("job", job).param("id", found).update();
            } else store.telemetry.reused();
            return snapshot;
        });
    }
    @Override public boolean processNext(UUID tenant) {
        Optional<ClaimedJob> claim = jobs.claimNext(tenant, "GRAPH_BUILD");
        if (claim.isEmpty()) { retireAbandoned(tenant); return false; }
        ClaimedJob job = claim.orElseThrow(); UUID snapshotId;
        try { snapshotId = UUID.fromString(mapper.readTree(job.payloadJson()).path("snapshot_id").asText()); }
        catch (RuntimeException malformed) { jobs.fail(tenant, job.id(), job.leaseToken(), FailureCategory.INVALID_INPUT, "GRAPH_JOB_INVALID"); return true; }
        try(var correlation=org.slf4j.MDC.putCloseable("correlation_id",job.correlationId().toString());
            var cause=org.slf4j.MDC.putCloseable("causation_id",Objects.toString(job.causationId(),job.id().toString()));
            var jobLog=org.slf4j.MDC.putCloseable("job_id",job.id().toString());
            var snapshotLog=org.slf4j.MDC.putCloseable("snapshot_id",snapshotId.toString());
            var tenantLog=org.slf4j.MDC.putCloseable("tenant_id",tenant.toString())) {
        long started=System.nanoTime();LOG.info("graph_build_claimed");
        try {
            Snapshot snapshot = store.transaction(tenant, () -> {
                store.lease(job); Snapshot current = store.locked(snapshotId);
                String expectedConfig=store.jdbc.sql("SELECT configuration_digest FROM graph_snapshots WHERE id=:id").param("id",snapshotId).query(String.class).single();
                if(!current.builderVersion().equals(engine.builderVersion()) || !expectedConfig.equals(engine.configurationDigest()))
                    throw new RepositoryMaterialSource.Failure(FailureCategory.STALE_SOURCE,null);
                if (!Set.of("READY", "PARTIAL").contains(current.status())) store.jdbc.sql("UPDATE graph_snapshots SET status='BUILDING' WHERE id=:id").param("id", snapshotId).update();
                return current;
            });
            if (Set.of("READY", "PARTIAL").contains(snapshot.status())) { jobs.complete(tenant, job.id(), job.leaseToken()); return true; }
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
            long[] renewal = {0};
            Runnable checkpoint = () -> {
                long now = System.nanoTime();
                if (now >= deadline) throw new RepositoryMaterialSource.Failure(FailureCategory.TIMEOUT, null);
                if (now >= renewal[0]) { store.lease(job); renewal[0] = now + renewalNanos; }
            };
            var limits = engine.limits();
            LOG.info("graph_source_retrieval_started");
            var material = source.fetch(tenant, snapshot.repositoryId(), snapshot.sourceSha(),
                    new RepositoryMaterialSource.Bounds(limits.files(), limits.totalBytes(), limits.fileBytes(), 60, GraphEngine.EXCLUSIONS), checkpoint);
            if (!snapshot.sourceSha().equals(material.sourceSha())) throw new RepositoryMaterialSource.Failure(FailureCategory.STALE_SOURCE, null);
            var product = engine.build(material.files(), material.gaps(), checkpoint);
            LOG.info("graph_persistence_started");
            store.publish(tenant, snapshotId, product, job, () -> source.requireAuthority(tenant, snapshot.repositoryId()));
            LOG.info("graph_snapshot_published");
        } catch (RuntimeException failure) {
            // Fencing is checked again before recording a failure; a stale worker cannot affect its successor.
            if (!jobs.renew(tenant, job.id(), job.leaseToken())) return true;
            FailureCategory category = failure instanceof RepositoryMaterialSource.Failure f ? f.category() : FailureCategory.DEPENDENCY_UNAVAILABLE;
            Instant retryAt = failure instanceof RepositoryMaterialSource.Failure f ? f.retryAt() : null;
            store.transaction(tenant, () -> {
                JobStatus status = jobs.failNotBefore(tenant, job.id(), job.leaseToken(), category, "GRAPH_" + category.name(), retryAt);
                store.telemetry.failed();
                store.jdbc.sql("UPDATE graph_snapshots SET status=:status,failure_category=:category WHERE id=:id AND status NOT IN ('READY','PARTIAL')")
                        .param("status", status == JobStatus.READY ? "QUEUED" : "FAILED").param("category", category.name()).param("id", snapshotId).update();
                return null;
            });
            LOG.warn("graph_build_failed category={}",category.name());
        } finally {store.telemetry.buildDuration(System.nanoTime()-started);}
        }
        return true;
    }
    private void retireAbandoned(UUID tenant) {
        store.transaction(tenant, () -> {
            var abandoned = store.jdbc.sql("SELECT id,job_id FROM graph_snapshots WHERE status IN ('QUEUED','BUILDING') AND job_id IS NOT NULL ORDER BY created_at LIMIT 100")
                    .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list();
            for (var row : abandoned) {
                var state = jobs.status(tenant, row.getValue());
                if (state.isPresent() && Set.of(JobStatus.DEAD, JobStatus.FAILED).contains(state.get()))
                    store.jdbc.sql("UPDATE graph_snapshots SET status='FAILED',failure_category='JOB_TERMINAL' WHERE id=:id AND job_id=:job AND status IN ('QUEUED','BUILDING')")
                            .param("id", row.getKey()).param("job", row.getValue()).update();
            }
            return null;
        });
    }
}
