package io.guidein.graph.application;

import io.guidein.graph.api.*;
import io.guidein.graph.api.GraphModel.*;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.platform.api.*;
import io.guidein.tenancy.api.RepositoryQuery;
import java.util.*;

public final class GraphQueries implements SystemGraph {
    private final GraphStore store;private final RepositoryQuery repositories;private final GraphBuilds builds;
    public GraphQueries(GraphStore store,RepositoryQuery repositories,GraphBuilds builds){this.store=store;this.repositories=repositories;this.builds=builds;}
    @Override public Snapshot request(AuthenticatedSubject subject,UUID tenant,UUID repository,String sha,UUID correlation){return builds.request(subject,tenant,repository,sha,correlation);}
    @Override public boolean processNext(UUID tenant){return builds.processNext(tenant);}
    private Snapshot authorized(AuthenticatedSubject subject,UUID tenant,UUID id,boolean published){
        repositories.requireMembership(subject,tenant);Snapshot snapshot=store.snapshot(id);
        try { repositories.get(subject,tenant,snapshot.repositoryId()); }
        catch(GuideInException denied) {
            // Graph UUID lookup must not reveal whether a snapshot exists outside the caller's repository scope.
            if(denied.code()==ErrorCode.AUTHORIZATION_DENIED)throw new GuideInException(ErrorCode.RESOURCE_NOT_FOUND);
            throw denied;
        }
        if(published && !Set.of("READY","PARTIAL").contains(snapshot.status()))throw new GuideInException(ErrorCode.CONFLICT,"Graph is not published.");
        return snapshot;
    }
    @Override public Snapshot snapshot(AuthenticatedSubject subject,UUID tenant,UUID id){return store.transaction(tenant,()->authorized(subject,tenant,id,false));}
    private int page(int offset,int limit){if(offset<0 || offset>1_000_000 || limit<1)throw new GuideInException(ErrorCode.VALIDATION_FAILED);return Math.min(limit,200);}
    @Override public Page<NodeView> nodes(AuthenticatedSubject subject,UUID tenant,UUID snapshot,int offset,int requestedLimit){
        int limit=page(offset,requestedLimit);return store.transaction(tenant,()->{
            authorized(subject,tenant,snapshot,true);
            var items=store.jdbc.sql("SELECT * FROM graph_nodes WHERE tenant_id=:tenant AND snapshot_id=:snapshot ORDER BY node_key OFFSET :offset LIMIT :limit")
                    .param("tenant",tenant).param("snapshot",snapshot).param("offset",offset).param("limit",limit).query(store::nodeRow).list();
            return new Page<>(items,offset,limit,count("graph_nodes",snapshot));
        });
    }
    @Override public Page<EdgeView> edges(AuthenticatedSubject subject,UUID tenant,UUID snapshot,int offset,int requestedLimit){
        int limit=page(offset,requestedLimit);return store.transaction(tenant,()->{
            authorized(subject,tenant,snapshot,true);
            var items=store.jdbc.sql("""
                    SELECT e.id,e.from_node_id,e.to_node_id,e.edge_type,f.node_key AS from_key,t.node_key AS to_key FROM graph_edges e
                    JOIN graph_nodes f ON f.tenant_id=e.tenant_id AND f.snapshot_id=e.snapshot_id AND f.id=e.from_node_id
                    JOIN graph_nodes t ON t.tenant_id=e.tenant_id AND t.snapshot_id=e.snapshot_id AND t.id=e.to_node_id
                    WHERE e.tenant_id=:tenant AND e.snapshot_id=:snapshot ORDER BY f.node_key,e.edge_type,t.node_key OFFSET :offset LIMIT :limit
                    """).param("tenant",tenant).param("snapshot",snapshot).param("offset",offset).param("limit",limit)
                    .query((rs,n)->new EdgeView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                            new Edge(rs.getString(5),EdgeType.valueOf(rs.getString(4)),rs.getString(6),store.evidence(rs.getObject(1,UUID.class))))).list();
            return new Page<>(items,offset,limit,count("graph_edges",snapshot));
        });
    }
    @Override public Page<GapView> gaps(AuthenticatedSubject subject,UUID tenant,UUID snapshot,int offset,int requestedLimit){
        int limit=page(offset,requestedLimit);return store.transaction(tenant,()->{
            authorized(subject,tenant,snapshot,true);
            var items=store.jdbc.sql("SELECT category,source_path,source_locator FROM graph_extraction_gaps WHERE snapshot_id=:snapshot ORDER BY category,source_path,source_locator OFFSET :offset LIMIT :limit")
                    .param("snapshot",snapshot).param("offset",offset).param("limit",limit).query((rs,n)->explain(new Gap(rs.getString(1),rs.getString(2),rs.getString(3)))).list();
            return new Page<>(items,offset,limit,count("graph_extraction_gaps",snapshot));
        });
    }
    @Override public NodeView node(AuthenticatedSubject subject,UUID tenant,UUID node){return store.transaction(tenant,()->{
        repositories.requireMembership(subject,tenant);
        UUID snapshot=store.jdbc.sql("SELECT snapshot_id FROM graph_nodes WHERE id=:id").param("id",node).query(UUID.class).optional().orElseThrow(()->new GuideInException(ErrorCode.RESOURCE_NOT_FOUND));
        authorized(subject,tenant,snapshot,true);return store.jdbc.sql("SELECT * FROM graph_nodes WHERE id=:id").param("id",node).query(store::nodeRow).single();
    });}
    @Override public Traversal traverse(AuthenticatedSubject subject,UUID tenant,UUID snapshot,UUID start,boolean reverse,Set<EdgeType> types,int depth,int nodeBudget){
        long started=System.nanoTime();
        var result=store.transaction(tenant,()->{authorized(subject,tenant,snapshot,true);return new GraphTraversal(store).run(tenant,snapshot,start,reverse,types,depth,nodeBudget);});
        store.telemetry.traversed(System.nanoTime()-started,result.truncated());return result;
    }
    @Override public Diff diff(AuthenticatedSubject subject,UUID tenant,UUID before,UUID after){return store.transaction(tenant,()->{
        Snapshot left=authorized(subject,tenant,before,true),right=authorized(subject,tenant,after,true);
        if(!left.repositoryId().equals(right.repositoryId()))throw new GuideInException(ErrorCode.VALIDATION_FAILED,"Diff requires snapshots from one repository.");
        return new GraphDiff(store).run(tenant,before,after);
    });}
    private long count(String table,UUID snapshot){return store.jdbc.sql("SELECT count(*) FROM "+table+" WHERE snapshot_id=:snapshot").param("snapshot",snapshot).query(Long.class).single();}
    private GapView explain(Gap gap) {
        String category=gap.category();String extractor="GRAPH";
        for(Source source:Source.values())if(category.startsWith(source.name()+"_"))extractor=source.name();
        if(category.equals("UNRESOLVED_SYMBOL"))extractor="JAVA";
        if(category.startsWith("PATH_")||category.startsWith("SOURCE_")||category.startsWith("FILE_"))extractor="SOURCE_ACQUISITION";
        String reason=switch(category) {
            case "UNRESOLVED_SYMBOL" -> "A referenced symbol could not be resolved from repository-contained declarations.";
            case "JAVA_PARSE_FAILED" -> "The Java source could not be parsed safely.";
            case "OPENAPI_REF_BLOCKED" -> "A reference is outside the permitted repository-local reference policy.";
            case "SOURCE_CONFLICT" -> "Explicit sources contradict one another; no unambiguous value was selected.";
            case "GRAPH_BUDGET_EXCEEDED","SOURCE_BUDGET_EXCEEDED","FILE_BYTE_LIMIT" -> "A configured processing budget prevented complete extraction.";
            default -> "This source could not be fully interpreted within the supported deterministic extraction rules.";
        };
        return new GapView(category,gap.path(),gap.locator(),reason,extractor,"MATERIAL");
    }
}
