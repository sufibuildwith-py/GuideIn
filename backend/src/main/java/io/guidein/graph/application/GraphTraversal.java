package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.EdgeType;
import io.guidein.graph.api.SystemGraph.Traversal;
import io.guidein.platform.api.ErrorCode;
import io.guidein.platform.api.GuideInException;
import java.util.*;

/** Breadth-first recursive state, not unbounded path enumeration. Every expansion has a work cap. */
final class GraphTraversal {
    static final int MAX_DEPTH=8, MAX_NODES=2000, MAX_EDGE_WORK=20_000;
    private final GraphStore store;
    GraphTraversal(GraphStore store){this.store=store;}
    Traversal run(UUID tenant,UUID snapshot,UUID start,boolean reverse,Set<EdgeType> types,int requestedDepth,int requestedNodes){
        if(requestedDepth<0 || requestedNodes<1)throw new GuideInException(ErrorCode.VALIDATION_FAILED);
        int depth=Math.min(requestedDepth,MAX_DEPTH),budget=Math.min(requestedNodes,MAX_NODES);
        var filters=(types==null || types.isEmpty()?EnumSet.allOf(EdgeType.class):types).stream().map(Enum::name).sorted().toList();
        boolean exists=store.jdbc.sql("SELECT EXISTS(SELECT 1 FROM graph_nodes WHERE tenant_id=:tenant AND snapshot_id=:snapshot AND id=:start)")
                .param("tenant",tenant).param("snapshot",snapshot).param("start",start).query(Boolean.class).single();
        if(!exists)throw new GuideInException(ErrorCode.RESOURCE_NOT_FOUND);
        store.jdbc.sql("SELECT set_config('statement_timeout','2000',true)").query(String.class).single();
        String sql=sql(reverse);
        record Result(List<UUID> ids,int level,long work,boolean nodeCut,boolean edgeCut,int frontier){}
        Result result=store.jdbc.sql(sql).param("start",start).param("tenant",tenant).param("snapshot",snapshot).param("types",filters)
                .param("workBudget",MAX_EDGE_WORK).param("nodeBudget",budget).param("depth",depth)
                .query((rs,n)->{
                    java.sql.Array array=rs.getArray("visited");Object[] raw=(Object[])array.getArray();var ids=new ArrayList<UUID>();
                    for(Object id:raw)ids.add((UUID)id);array.free();
                    return new Result(ids,rs.getInt("level"),rs.getLong("scanned"),rs.getBoolean("node_cut"),rs.getBoolean("edge_cut"),rs.getInt("frontier_count"));
                }).single();
        var reasons=new ArrayList<String>();
        if(result.nodeCut || result.ids.size()>=budget && result.frontier>0)reasons.add("NODE_BUDGET_EXCEEDED");
        if(result.level>=depth && result.frontier>0)reasons.add("DEPTH_LIMIT_REACHED");
        if(result.edgeCut || result.work>=MAX_EDGE_WORK)reasons.add("EDGE_WORK_BUDGET_EXCEEDED");
        var nodes=store.jdbc.sql("SELECT * FROM graph_nodes WHERE tenant_id=:tenant AND snapshot_id=:snapshot AND id IN (:ids) ORDER BY node_key")
                .param("tenant",tenant).param("snapshot",snapshot).param("ids",result.ids).query(store::nodeRow).list();
        return new Traversal(nodes,result.level,result.work,!reasons.isEmpty(),reasons);
    }
    static String sql(boolean reverse){
        String from=reverse?"to_node_id":"from_node_id",to=reverse?"from_node_id":"to_node_id";
        return """
                WITH RECURSIVE walk(frontier,visited,level,scanned,node_cut,edge_cut) AS (
                  SELECT ARRAY[CAST(:start AS uuid)],ARRAY[CAST(:start AS uuid)],0,0::bigint,false,false
                  UNION ALL
                  SELECT step.next_nodes,w.visited||step.next_nodes,w.level+1,w.scanned+step.work,
                         w.node_cut OR step.node_cut,w.edge_cut OR step.edge_cut
                  FROM walk w
                  CROSS JOIN LATERAL (
                    WITH candidates AS MATERIALIZED (
                      SELECT f.node,e.destination
                      FROM unnest(w.frontier) f(node)
                      CROSS JOIN LATERAL (
                        SELECT %s AS destination FROM graph_edges
                        WHERE tenant_id=:tenant AND snapshot_id=:snapshot AND %s=f.node AND edge_type IN (:types)
                        ORDER BY edge_type,%s
                        LIMIT LEAST(256,(:workBudget-w.scanned)/GREATEST(cardinality(w.frontier),1))
                      ) e
                    ), unseen AS MATERIALIZED (
                      SELECT DISTINCT destination FROM candidates WHERE NOT(destination=ANY(w.visited))
                    )
                    SELECT ARRAY(SELECT destination FROM unseen ORDER BY destination LIMIT :nodeBudget-cardinality(w.visited)) AS next_nodes,
                           (SELECT count(*) FROM candidates) AS work,
                           (SELECT count(*) FROM unseen)>:nodeBudget-cardinality(w.visited) AS node_cut,
                           ((:workBudget-w.scanned)<cardinality(w.frontier) OR EXISTS (
                             SELECT 1 FROM candidates GROUP BY node
                             HAVING count(*)>=LEAST(256,(:workBudget-w.scanned)/GREATEST(cardinality(w.frontier),1))
                           )) AS edge_cut
                  ) step
                  WHERE w.level<:depth AND cardinality(w.frontier)>0 AND cardinality(w.visited)<:nodeBudget AND w.scanned<:workBudget
                )
                SELECT visited,level,scanned,node_cut,edge_cut,cardinality(frontier) AS frontier_count
                FROM walk ORDER BY level DESC LIMIT 1
                """.formatted(to,from,to);
    }
}
