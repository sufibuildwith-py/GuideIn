package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import io.guidein.jobs.api.ClaimedJob;
import io.guidein.platform.api.CanonicalJson;
import java.util.*;

/** Test access to the real persistence implementation; no alternate storage or production bypass. */
public final class GraphProofAccess {
    private GraphProofAccess(){}
    public static void publish(GraphStore store,UUID tenant,UUID snapshot,GraphEngine.Product product,ClaimedJob job,Runnable authority){store.publish(tenant,snapshot,product,job,authority);}
    public static Content reconstruct(GraphStore store,UUID tenant,UUID snapshot,CanonicalJson canonical) {
        return store.transaction(tenant,()->{
            var state=store.snapshot(snapshot);
            var nodes=store.jdbc.sql("SELECT * FROM graph_nodes WHERE snapshot_id=:id ORDER BY node_key").param("id",snapshot).query(store::nodeRow).list().stream().map(n->n.node()).toList();
            var edges=store.jdbc.sql("""
                    SELECT e.id,e.edge_type,f.node_key,t.node_key FROM graph_edges e
                    JOIN graph_nodes f ON f.tenant_id=e.tenant_id AND f.snapshot_id=e.snapshot_id AND f.id=e.from_node_id
                    JOIN graph_nodes t ON t.tenant_id=e.tenant_id AND t.snapshot_id=e.snapshot_id AND t.id=e.to_node_id
                    WHERE e.snapshot_id=:id
                    """).param("id",snapshot).query((rs,n)->new Edge(rs.getString(3),EdgeType.valueOf(rs.getString(2)),rs.getString(4),
                    store.evidence(rs.getObject(1,UUID.class)).stream().sorted(Comparator.comparing(e->new String(canonical.canonicalize(e),java.nio.charset.StandardCharsets.UTF_8))).toList())).list().stream().sorted(Comparator.comparing(Edge::key)).toList();
            var gaps=store.jdbc.sql("SELECT category,source_path,source_locator FROM graph_extraction_gaps WHERE snapshot_id=:id").param("id",snapshot)
                    .query((rs,n)->new Gap(rs.getString(1),rs.getString(2),rs.getString(3))).list().stream().sorted(Comparator.comparing(g->new String(canonical.canonicalize(g),java.nio.charset.StandardCharsets.UTF_8))).toList();
            return new Content(state.builderVersion(),Source.REGISTRY_VERSION,nodes,edges,gaps);
        });
    }
    public static Map<String,List<String>> explainScale(GraphStore store,UUID tenant,UUID before,UUID after,UUID start){
        return store.transaction(tenant,()->{
            store.jdbc.sql("SELECT set_config('statement_timeout','30000',true)").query(String.class).single();
            var plans=new TreeMap<String,List<String>>();
            plans.put("canonical_node_lookup",store.jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,SETTINGS) SELECT * FROM graph_nodes WHERE tenant_id=:tenant AND snapshot_id=:snapshot AND node_key=:key")
                    .param("tenant",tenant).param("snapshot",before).param("key","synthetic:00250").query(String.class).list());
            for(boolean reverse:List.of(false,true))plans.put(reverse?"reverse_edge_lookup":"forward_edge_lookup",store.jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,SETTINGS) SELECT * FROM graph_edges WHERE tenant_id=:tenant AND snapshot_id=:snapshot AND "+(reverse?"to_node_id":"from_node_id")+"=:node AND edge_type='DEPENDS_ON'")
                    .param("tenant",tenant).param("snapshot",before).param("node",start).query(String.class).list());
            plans.put("type_query",store.jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,SETTINGS) SELECT * FROM graph_nodes WHERE tenant_id=:tenant AND snapshot_id=:snapshot AND node_type='MODULE' ORDER BY node_key LIMIT 200")
                    .param("tenant",tenant).param("snapshot",before).query(String.class).list());
            plans.put("recursive_traversal",store.jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,SETTINGS) "+GraphTraversal.sql(false))
                    .param("start",start).param("tenant",tenant).param("snapshot",before).param("types",List.of("DEPENDS_ON"))
                    .param("workBudget",20_000).param("nodeBudget",2_000).param("depth",8).query(String.class).list());
            plans.put("diff_support",store.jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,SETTINGS) "+GraphDiff.edgeSql())
                    .param("tenant",tenant).param("a",before).param("b",after).param("limit",1_000).query(String.class).list());
            plans.put("evidence_coverage_validation",store.jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,SETTINGS) SELECT count(DISTINCT edge_id) FROM graph_edge_evidence WHERE tenant_id=:tenant AND snapshot_id=:snapshot")
                    .param("tenant",tenant).param("snapshot",before).query(String.class).list());
            return plans;
        });
    }
}
