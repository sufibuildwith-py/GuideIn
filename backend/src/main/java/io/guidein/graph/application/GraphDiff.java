package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.Gap;
import io.guidein.graph.api.SystemGraph.Diff;
import java.util.*;

/** Presence and evidence comparisons use semantic keys; database UUIDs never define a difference. */
final class GraphDiff {
    private static final int LIMIT=1000;
    private final GraphStore store;
    private record Item(String key,long total){}
    private record GapItem(Gap gap,long total){}
    GraphDiff(GraphStore store){this.store=store;}
    Diff run(UUID tenant,UUID before,UUID after){
        store.jdbc.sql("SELECT set_config('statement_timeout','30000',true)").query(String.class).single();
        var removedNodes=nodes(tenant,before,after,false);var addedNodes=nodes(tenant,after,before,false);var changedNodes=nodes(tenant,before,after,true);
        var edgeChanges=edges(tenant,before,after);var removedEdges=edgeChanges.get("REMOVED");
        var addedEdges=edgeChanges.get("ADDED");var changedEdges=edgeChanges.get("CHANGED");
        var removedGaps=gaps(tenant,before,after);var addedGaps=gaps(tenant,after,before);
        var totals=new TreeMap<String,Long>();
        totals.put("added_nodes",total(addedNodes));totals.put("removed_nodes",total(removedNodes));totals.put("changed_nodes",total(changedNodes));
        totals.put("added_edges",total(addedEdges));totals.put("removed_edges",total(removedEdges));totals.put("changed_edges",total(changedEdges));
        totals.put("added_gaps",addedGaps.isEmpty()?0:addedGaps.getFirst().total);totals.put("removed_gaps",removedGaps.isEmpty()?0:removedGaps.getFirst().total);
        return new Diff(keys(addedNodes),keys(removedNodes),keys(changedNodes),keys(addedEdges),keys(removedEdges),keys(changedEdges),
                addedGaps.stream().map(GapItem::gap).toList(),removedGaps.stream().map(GapItem::gap).toList(),totals,totals.values().stream().anyMatch(n->n>LIMIT));
    }
    private List<Item> nodes(UUID tenant,UUID a,UUID b,boolean changed){
        String condition=changed?"b.id IS NOT NULL AND (a.node_type,a.display_name,a.criticality,a.source_identity,a.metadata) IS DISTINCT FROM (b.node_type,b.display_name,b.criticality,b.source_identity,b.metadata)":"b.id IS NULL";
        return store.jdbc.sql("""
                SELECT a.node_key,count(*) OVER() AS total FROM graph_nodes a
                LEFT JOIN graph_nodes b ON b.tenant_id=:tenant AND b.snapshot_id=:b AND b.node_key=a.node_key
                WHERE a.tenant_id=:tenant AND a.snapshot_id=:a AND %s ORDER BY a.node_key LIMIT :limit
                """.formatted(condition)).param("tenant",tenant).param("a",a).param("b",b).param("limit",LIMIT)
                .query((rs,n)->new Item(rs.getString(1),rs.getLong(2))).list();
    }
    private Map<String,List<Item>> edges(UUID tenant,UUID a,UUID b){
        var rows=store.jdbc.sql(edgeSql()).param("tenant",tenant).param("a",a).param("b",b).param("limit",LIMIT)
                .query((rs,n)->Map.entry(rs.getString(1),new Item(rs.getString(2).length()+":"+rs.getString(2)+":"+rs.getString(3)+":"+rs.getString(4),rs.getLong(5)))).list();
        var result=new TreeMap<String,List<Item>>();for(String kind:List.of("ADDED","REMOVED","CHANGED"))result.put(kind,new ArrayList<>());
        rows.forEach(row->result.get(row.getKey()).add(row.getValue()));return result;
    }
    static String edgeSql(){
        String view="""
                SELECT e.id,e.edge_type,e.from_node_key AS from_key,e.to_node_key AS to_key,e.evidence_digest AS evidence
                FROM graph_edges e
                WHERE e.tenant_id=:tenant AND e.snapshot_id=%s
                """;
        return "WITH a AS ("+view.formatted(":a")+"),b AS ("+view.formatted(":b")+"),"+"""
                merged AS (
                  SELECT coalesce(a.from_key,b.from_key) from_key,coalesce(a.edge_type,b.edge_type) edge_type,
                         coalesce(a.to_key,b.to_key) to_key,a.id a_id,b.id b_id,a.evidence a_evidence,b.evidence b_evidence
                  FROM a FULL JOIN b ON a.from_key=b.from_key AND a.edge_type=b.edge_type AND a.to_key=b.to_key
                ), classified AS (
                  SELECT *,CASE WHEN a_id IS NULL THEN 'ADDED' WHEN b_id IS NULL THEN 'REMOVED' ELSE 'CHANGED' END kind
                  FROM merged WHERE a_id IS NULL OR b_id IS NULL OR a_evidence IS DISTINCT FROM b_evidence
                ), ranked AS (
                  SELECT *,count(*) OVER(PARTITION BY kind) total,
                         row_number() OVER(PARTITION BY kind ORDER BY from_key,edge_type,to_key) ordinal FROM classified
                )
                SELECT kind,from_key,edge_type,to_key,total FROM ranked WHERE ordinal<=:limit ORDER BY kind,ordinal
                """;
    }
    private List<GapItem> gaps(UUID tenant,UUID a,UUID b){
        return store.jdbc.sql("""
                SELECT a.category,a.source_path,a.source_locator,count(*) OVER() AS total FROM graph_extraction_gaps a
                LEFT JOIN graph_extraction_gaps b ON b.tenant_id=:tenant AND b.snapshot_id=:b
                    AND (a.category,a.source_path,a.source_locator)=(b.category,b.source_path,b.source_locator)
                WHERE a.tenant_id=:tenant AND a.snapshot_id=:a AND b.id IS NULL
                ORDER BY a.category,a.source_path,a.source_locator LIMIT :limit
                """).param("tenant",tenant).param("a",a).param("b",b).param("limit",LIMIT)
                .query((rs,n)->new GapItem(new Gap(rs.getString(1),rs.getString(2),rs.getString(3)),rs.getLong(4))).list();
    }
    private long total(List<Item> items){return items.isEmpty()?0:items.getFirst().total;}
    private List<String> keys(List<Item> items){return items.stream().map(Item::key).sorted().toList();}
}
