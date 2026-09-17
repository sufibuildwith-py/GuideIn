package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import io.guidein.graph.api.SystemGraph.*;
import io.guidein.jobs.api.ClaimedJob;
import io.guidein.jobs.api.JobQueue;
import io.guidein.events.api.OutboxCommand;
import io.guidein.events.api.OutboxWriter;
import io.guidein.platform.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class GraphStore {
    private static final int BATCH_SIZE=100;
    public record PublishTimings(long nodesNanos,long edgesNanos,long evidenceNanos,long gapsNanos,
                                 long validationNanos,long finalizationNanos,long commitNanos,long totalNanos,
                                 int evidenceRows) {}
    private final AtomicReference<PublishTimings> lastPublishTimings=new AtomicReference<>();
    final GraphTelemetry telemetry;
    final JdbcClient jdbc; final JdbcTemplate batch;
    private final TenantContext context; private final TransactionTemplate transactions;
    private final CanonicalJson canonical; private final JobQueue jobs; private final OutboxWriter outbox;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public GraphStore(JdbcClient jdbc, JdbcTemplate batch, TenantContext context, PlatformTransactionManager manager,
                      CanonicalJson canonical, JobQueue jobs, OutboxWriter outbox) {
        this(jdbc,batch,context,manager,canonical,jobs,outbox,GraphTelemetry.disabled());
    }
    public GraphStore(JdbcClient jdbc,JdbcTemplate batch,TenantContext context,PlatformTransactionManager manager,
                      CanonicalJson canonical,JobQueue jobs,OutboxWriter outbox,GraphTelemetry telemetry) {
        this.jdbc = jdbc; this.batch = batch; this.context = context; transactions = new TransactionTemplate(manager);
        this.canonical = canonical; this.jobs = jobs; this.outbox = outbox;
        this.telemetry=telemetry;
    }
    <T> T transaction(UUID tenant, Supplier<T> work) { return transactions.execute(status -> { context.setTenant(tenant); return work.get(); }); }
    String json(Object value) { return new String(canonical.canonicalize(value), StandardCharsets.UTF_8); }
    Snapshot snapshot(UUID id) {
        return jdbc.sql("SELECT id,repository_id,source_sha,builder_version,status,canonical_digest,node_count,edge_count,gap_count FROM graph_snapshots WHERE id=:id")
                .param("id", id).query((rs, n) -> new Snapshot(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)))
                .optional().orElseThrow(() -> new GuideInException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    void publish(UUID tenant, UUID snapshot, GraphEngine.Product product, ClaimedJob job, Runnable authority) {
        long started=System.nanoTime();long[] stages=new long[7];int[] evidenceRows={0};
        transaction(tenant, () -> {
            lease(job); authority.run();
            Snapshot state = locked(snapshot);
            if (Set.of("READY", "PARTIAL").contains(state.status())) {
                if (!jobs.complete(tenant, job.id(), job.leaseToken())) throw new GuideInException(ErrorCode.CONFLICT); return null;
            }
            jdbc.sql("UPDATE graph_snapshots SET status='BUILDING',failure_category=NULL WHERE id=:id").param("id", snapshot).update();
            for (String table : List.of("graph_edge_evidence", "graph_edges", "graph_extraction_gaps", "graph_nodes"))
                jdbc.sql("DELETE FROM " + table + " WHERE snapshot_id=:id").param("id", snapshot).update();
            Map<String, UUID> nodeIds = new HashMap<>(); List<Node> nodes = product.content().nodes();
            nodes.forEach(node -> nodeIds.put(node.key(), UUID.randomUUID()));
            long stage=System.nanoTime();
            for (int offset = 0; offset < nodes.size(); offset += BATCH_SIZE) {
                lease(job);
                batch.batchUpdate("INSERT INTO graph_nodes(id,tenant_id,snapshot_id,node_key,node_type,display_name,criticality,source_identity,metadata) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
                        nodes.subList(offset, Math.min(nodes.size(), offset + BATCH_SIZE)), BATCH_SIZE, (ps, node) -> {
                            ps.setObject(1, nodeIds.get(node.key())); ps.setObject(2, tenant); ps.setObject(3, snapshot);
                            ps.setString(4, node.key()); ps.setString(5, node.type().name()); ps.setString(6, node.displayName());
                            ps.setString(7, node.criticality()); ps.setString(8, node.sourceIdentity()); ps.setString(9, json(node.metadata()));
                        });
            }
            stages[0]=System.nanoTime()-stage;
            List<Edge> edges = product.content().edges();
            record EdgeRow(UUID id, Edge edge) {} record EvidenceRow(UUID edge, Evidence evidence) {}
            long edgeNanos=0,evidenceNanos=0;
            for (int offset = 0; offset < edges.size(); offset += BATCH_SIZE) {
                lease(job); List<EdgeRow> rows = edges.subList(offset, Math.min(edges.size(), offset + BATCH_SIZE)).stream().map(e -> new EdgeRow(UUID.randomUUID(), e)).toList();
                stage=System.nanoTime();
                batch.batchUpdate("INSERT INTO graph_edges(id,tenant_id,snapshot_id,from_node_id,to_node_id,from_node_key,to_node_key,edge_type,evidence_digest) VALUES (?,?,?,?,?,?,?,?,?)", rows, BATCH_SIZE, (ps, row) -> {
                    ps.setObject(1, row.id()); ps.setObject(2, tenant); ps.setObject(3, snapshot);
                    ps.setObject(4, nodeIds.get(row.edge().from())); ps.setObject(5, nodeIds.get(row.edge().to()));
                    ps.setString(6,row.edge().from());ps.setString(7,row.edge().to());ps.setString(8, row.edge().type().name());ps.setString(9,evidenceDigest(row.edge()));
                });
                edgeNanos+=System.nanoTime()-stage;
                List<EvidenceRow> evidence = rows.stream().flatMap(row -> row.edge().evidence().stream().map(e -> new EvidenceRow(row.id(), e))).toList();
                evidenceRows[0]+=evidence.size();
                for(int evidenceOffset=0;evidenceOffset<evidence.size();evidenceOffset+=BATCH_SIZE) {
                lease(job);
                stage=System.nanoTime();
                batch.batchUpdate("""
                        INSERT INTO graph_edge_evidence(id,tenant_id,snapshot_id,edge_id,evidence_key,source_type,source_path,source_locator,
                            source_digest,extractor_version,observation_type,trust_class,confidence,metadata)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                        """, evidence.subList(evidenceOffset,Math.min(evidence.size(),evidenceOffset+BATCH_SIZE)), BATCH_SIZE, (ps, row) -> {
                    Evidence e = row.evidence(); ps.setObject(1, UUID.randomUUID()); ps.setObject(2, tenant); ps.setObject(3, snapshot); ps.setObject(4, row.edge());
                    ps.setString(5, HexFormat.of().formatHex(Digests.sha256(canonical.canonicalize(e)))); ps.setString(6, e.source().name());
                    ps.setString(7, e.path()); ps.setString(8, e.locator()); ps.setString(9, e.digest()); ps.setString(10, e.extractorVersion());
                    ps.setString(11, e.observation()); ps.setString(12, e.trust().name()); ps.setBigDecimal(13, new java.math.BigDecimal(e.confidence())); ps.setString(14, json(e.metadata()));
                });
                evidenceNanos+=System.nanoTime()-stage;
                }
            }
            stages[1]=edgeNanos;stages[2]=evidenceNanos;
            lease(job);
            stage=System.nanoTime();
            batch.batchUpdate("INSERT INTO graph_extraction_gaps(id,tenant_id,snapshot_id,category,source_path,source_locator) VALUES (?,?,?,?,?,?)", product.content().gaps(), 500, (ps, gap) -> {
                ps.setObject(1, UUID.randomUUID()); ps.setObject(2, tenant); ps.setObject(3, snapshot); ps.setString(4, gap.category()); ps.setString(5, gap.path()); ps.setString(6, gap.locator());
            });
            stages[3]=System.nanoTime()-stage;
            authority.run();
            stage=System.nanoTime();
            validatePersisted(tenant,snapshot,nodes.size(),edges.size(),evidenceRows[0],product.content().gaps().size());
            stages[4]=System.nanoTime()-stage;
            lease(job);
            stage=System.nanoTime();
            jdbc.sql("""
                    UPDATE graph_snapshots SET status=:status,canonical_digest=:digest,guidein_config_digest=:config,
                        node_count=:nodes,edge_count=:edges,gap_count=:gaps,published_at=clock_timestamp() WHERE id=:id
                    """).param("status", product.content().status()).param("digest", product.digest()).param("config", product.guideinConfigDigest())
                    .param("nodes", nodes.size()).param("edges", edges.size()).param("gaps", product.content().gaps().size()).param("id", snapshot).update();
            stages[5]=System.nanoTime()-stage;
            outbox.append(new OutboxCommand(tenant, "GRAPH_SNAPSHOT", snapshot, "graph.snapshot.created", 1,
                    Map.of("graph_snapshot_id", snapshot.toString(), "repository_id", state.repositoryId().toString(), "source_sha", state.sourceSha(),
                            "builder_version", state.builderVersion(), "canonical_digest", product.digest(), "status", product.content().status(),
                            "node_count", nodes.size(), "edge_count", edges.size(), "gap_count", product.content().gaps().size()), job.correlationId(), job.id(), Instant.now()));
            if (!jobs.complete(tenant, job.id(), job.leaseToken())) throw new GuideInException(ErrorCode.CONFLICT);
            telemetry.published(nodes.size(),edges.size(),product.content().gaps().size(),System.nanoTime()-started);
            stages[6]=System.nanoTime();
            return null;
        });
        long finished=System.nanoTime();
        lastPublishTimings.set(new PublishTimings(stages[0],stages[1],stages[2],stages[3],stages[4],stages[5],
                finished-stages[6],finished-started,evidenceRows[0]));
    }
    private void validatePersisted(UUID tenant,UUID snapshot,int nodes,int edges,int evidence,int gaps) {
        record Counts(long nodes,long edges,long evidence,long evidencedEdges,long gaps){}
        Counts actual=jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM graph_nodes WHERE tenant_id=:tenant AND snapshot_id=:snapshot),
                  (SELECT count(*) FROM graph_edges WHERE tenant_id=:tenant AND snapshot_id=:snapshot),
                  (SELECT count(*) FROM graph_edge_evidence WHERE tenant_id=:tenant AND snapshot_id=:snapshot),
                  (SELECT count(DISTINCT edge_id) FROM graph_edge_evidence WHERE tenant_id=:tenant AND snapshot_id=:snapshot),
                  (SELECT count(*) FROM graph_extraction_gaps WHERE tenant_id=:tenant AND snapshot_id=:snapshot)
                """).param("tenant",tenant).param("snapshot",snapshot).query((rs,row)->new Counts(
                rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5))).single();
        if(actual.nodes()!=nodes||actual.edges()!=edges||actual.evidence()!=evidence||actual.evidencedEdges()!=edges||
                actual.gaps()!=gaps)throw new GuideInException(ErrorCode.CONFLICT);
    }
    public PublishTimings lastPublishTimings(){return lastPublishTimings.get();}
    private String evidenceDigest(Edge edge) {
        List<String> keys=edge.evidence().stream().map(e->HexFormat.of().formatHex(Digests.sha256(canonical.canonicalize(e)))).sorted().toList();
        return HexFormat.of().formatHex(Digests.sha256(canonical.canonicalize(keys)));
    }
    Snapshot locked(UUID id) { jdbc.sql("SELECT id FROM graph_snapshots WHERE id=:id FOR UPDATE").param("id", id).query(UUID.class).optional().orElseThrow(() -> new GuideInException(ErrorCode.RESOURCE_NOT_FOUND)); return snapshot(id); }
    void lease(ClaimedJob job) { if (!jobs.renew(job.tenantId(), job.id(), job.leaseToken())) throw new GuideInException(ErrorCode.CONFLICT); }
    @SuppressWarnings("unchecked") Map<String, String> metadata(String json) { return mapper.readValue(json, Map.class); }
    NodeView nodeRow(ResultSet rs, int row) throws SQLException {
        return new NodeView(rs.getObject("id", UUID.class), rs.getObject("snapshot_id", UUID.class), new Node(rs.getString("node_key"), NodeType.valueOf(rs.getString("node_type")),
                rs.getString("display_name"), rs.getString("criticality"), rs.getString("source_identity"), metadata(rs.getString("metadata"))));
    }
    List<Evidence> evidence(UUID edge) {
        return jdbc.sql("SELECT * FROM graph_edge_evidence WHERE edge_id=:id ORDER BY evidence_key").param("id", edge)
                .query((rs, row) -> new Evidence(Source.valueOf(rs.getString("source_type")), rs.getString("source_path"), rs.getString("source_locator"),
                        rs.getString("source_digest"), rs.getString("extractor_version"), rs.getString("observation_type"), Trust.valueOf(rs.getString("trust_class")),
                        rs.getBigDecimal("confidence").toPlainString(), metadata(rs.getString("metadata")))).list();
    }
}
