package io.guidein.graph.api;

import io.guidein.identity.api.AuthenticatedSubject;
import java.util.*;

public interface SystemGraph {
    record Snapshot(UUID id, UUID repositoryId, String sourceSha, String builderVersion, String status,
                    String canonicalDigest, int nodeCount, int edgeCount, int gapCount) {}
    record NodeView(UUID id, UUID snapshotId, GraphModel.Node node) {}
    record EdgeView(UUID id, UUID fromNodeId, UUID toNodeId, GraphModel.Edge edge) {}
    record GapView(String category,String path,String locator,String reason,String extractor,String materiality) {}
    record Page<T>(List<T> items, int offset, int limit, long total) { public Page { items = List.copyOf(items); } }
    record Traversal(List<NodeView> nodes, int depth, long edgesScanned, boolean truncated, List<String> reasons) {
        public Traversal { nodes = List.copyOf(nodes); reasons = List.copyOf(reasons); }
    }
    record Diff(List<String> addedNodes, List<String> removedNodes, List<String> changedNodes,
                List<String> addedEdges, List<String> removedEdges, List<String> changedEdges,
                List<GraphModel.Gap> addedGaps, List<GraphModel.Gap> removedGaps,
                Map<String,Long> totals, boolean truncated) {}
    Snapshot request(AuthenticatedSubject subject, UUID tenant, UUID repository, String sha, UUID correlation);
    Snapshot snapshot(AuthenticatedSubject subject, UUID tenant, UUID snapshot);
    Page<NodeView> nodes(AuthenticatedSubject subject, UUID tenant, UUID snapshot, int offset, int limit);
    Page<EdgeView> edges(AuthenticatedSubject subject, UUID tenant, UUID snapshot, int offset, int limit);
    Page<GapView> gaps(AuthenticatedSubject subject, UUID tenant, UUID snapshot, int offset, int limit);
    NodeView node(AuthenticatedSubject subject, UUID tenant, UUID node);
    Traversal traverse(AuthenticatedSubject subject, UUID tenant, UUID snapshot, UUID start,
                       boolean reverse, Set<GraphModel.EdgeType> types, int depth, int nodeBudget);
    Diff diff(AuthenticatedSubject subject, UUID tenant, UUID before, UUID after);
    boolean processNext(UUID tenant);
}
