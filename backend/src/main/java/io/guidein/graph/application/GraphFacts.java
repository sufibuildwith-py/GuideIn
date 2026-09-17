package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import io.guidein.platform.api.CanonicalJson;
import io.guidein.platform.api.Digests;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One validator owns cardinality, semantic deduplication and canonical ordering. */
public final class GraphFacts {
    public static final String BUILDER_VERSION = "system-graph-v1";
    private final GraphLimits limits;
    private final CanonicalJson canonical;
    private final Runnable checkpoint;
    private final TreeMap<String, Node> nodes = new TreeMap<>();
    private final TreeMap<String, Edge> edges = new TreeMap<>();
    private final TreeMap<String, Gap> gaps = new TreeMap<>();
    private final Map<String,Source> nodeSources = new HashMap<>();
    private final TreeMap<String,TreeMap<String,String>> criticalities = new TreeMap<>();
    private Source source = Source.FILE_TREE;
    public GraphFacts(GraphLimits limits, CanonicalJson canonical) { this(limits, canonical, () -> {}); }
    public GraphFacts(GraphLimits limits, CanonicalJson canonical, Runnable checkpoint) { this.limits = limits; this.canonical = canonical; this.checkpoint = checkpoint; }
    public void checkpoint() { checkpoint.run(); }
    public void source(Source source) { this.source = Objects.requireNonNull(source); }
    /** Reconcile an independently collected extractor contribution without prematurely resolving endpoints. */
    public void merge(GraphFacts contribution) {
        for(Node candidate:contribution.nodes.values()) {
            source(contribution.nodeSources.get(candidate.key()));
            node(candidate.key(),candidate.type(),candidate.displayName(),candidate.sourceIdentity(),candidate.metadata());
        }
        contribution.edges.values().forEach(edge->edge.evidence().forEach(e->edge(edge.from(),edge.type(),edge.to(),e)));
        contribution.gaps.values().forEach(g->gap(g.category(),g.path(),g.locator()));
        contribution.criticalities.forEach((key,claims)->claims.forEach((path,value)->criticality(key,value,path)));
    }
    public boolean node(String key, NodeType type, String label, String path, Map<String, String> metadata) {
        checkpoint.run();
        if (key == null || key.isBlank() || key.getBytes(StandardCharsets.UTF_8).length > 2048) { gap("NODE_IDENTITY_INVALID", path, ""); return false; }
        Node candidate = new Node(key, type, label, "UNKNOWN", path, metadata);
        Node prior = nodes.get(key);
        if (prior != null) {
            Source priorSource = nodeSources.get(key);
            if (prior.type() != type) gap("NODE_IDENTITY_CONFLICT", "", key);
            // Source precedence first; canonical tie-break removes incidental invocation/completion order.
            if (source.precedence < priorSource.precedence || source.precedence == priorSource.precedence && encoded(candidate).compareTo(encoded(prior)) < 0) {
                nodes.put(key,candidate);nodeSources.put(key,source);
            }
            return true;
        }
        if (nodes.size() >= limits.nodes()) { gap("GRAPH_BUDGET_EXCEEDED", "", "nodes"); return false; }
        nodes.put(key, candidate);nodeSources.put(key,source); return true;
    }
    public void edge(String from, EdgeType type, String to, Evidence evidence) {
        checkpoint.run();
        // Endpoint validation happens after all extractors have contributed; candidates remain bounded below.
        Edge candidate = new Edge(from, type, to, List.of(evidence));
        Edge prior = edges.get(candidate.key());
        if (prior == null) {
            if (edges.size() >= limits.edges()) { gap("GRAPH_BUDGET_EXCEEDED", "", "edges"); return; }
            edges.put(candidate.key(), candidate);
        } else {
            TreeMap<String, Evidence> combined = new TreeMap<>();
            prior.evidence().forEach(e -> combined.put(encoded(e), e));
            combined.put(encoded(evidence), evidence);
            if (combined.size() > 128) { gap("EVIDENCE_BUDGET_EXCEEDED", evidence.path(), ""); return; }
            edges.put(candidate.key(), new Edge(from, type, to, List.copyOf(combined.values())));
        }
    }
    public Evidence evidence(Source source, SourceMaterial material, String path, String locator, String observation) {
        return new Evidence(source, path, locator, material.digest(path),
                source.name().toLowerCase(Locale.ROOT) + "-v1", observation, source.trust, source.confidence, Map.of());
    }
    public void gap(String category, String path, String locator) {
        if (locator.getBytes(StandardCharsets.UTF_8).length > 1024) {
            String digest = HexFormat.of().formatHex(Digests.sha256(locator.getBytes(StandardCharsets.UTF_8)));
            locator = "location-sha256:" + digest;
        }
        Gap gap = new Gap(category, path, locator);
        if (gaps.size() < limits.gaps() - 1) gaps.put(encoded(gap), gap);
        else {
            Gap truncated = new Gap("GAP_BUDGET_EXCEEDED", "", "");
            gaps.put(encoded(truncated), truncated);
        }
    }
    private String encoded(Object value) { return new String(canonical.canonicalize(value), StandardCharsets.UTF_8); }
    public boolean hasNode(String key) { return nodes.containsKey(key); }
    public int gapCount(){return gaps.size();}
    public void criticality(String key, String value, String sourcePath) {
        criticalities.computeIfAbsent(key,ignored->new TreeMap<>()).put(sourcePath,value);
    }
    public Content content() {
        var resolvedNodes=new ArrayList<Node>();var resolvedEdges=new ArrayList<Edge>();var resolvedGaps=new TreeMap<>(gaps);
        for(Node node:nodes.values()) {
            var claims=criticalities.get(node.key());String value=node.criticality();
            if(claims!=null) {
                var values=new TreeSet<>(claims.values());values.remove("UNKNOWN");
                if(values.size()==1)value=values.first();
                else if(values.size()>1) {
                    var conflict=new Gap("SOURCE_CONFLICT",claims.firstKey(),encoded(Map.of("subject",node.key(),"candidate_relation","criticality","sources",claims,"reason","Conflicting explicit criticality declarations")));
                    resolvedGaps.put(encoded(conflict),conflict);
                    value="UNKNOWN";
                }
            }
            resolvedNodes.add(new Node(node.key(),node.type(),node.displayName(),value,node.sourceIdentity(),node.metadata()));
        }
        for(Edge edge:edges.values()) {
            if(nodes.containsKey(edge.from()) && nodes.containsKey(edge.to()))resolvedEdges.add(edge);
            else for(Evidence evidence:edge.evidence()) {
                var gap=new Gap("EDGE_ENDPOINT_UNAVAILABLE",evidence.path(),evidence.locator());resolvedGaps.put(encoded(gap),gap);
            }
        }
        if(resolvedGaps.size()>limits.gaps()) {
            while(resolvedGaps.size()>=limits.gaps())resolvedGaps.pollLastEntry();
            var gap=new Gap("GAP_BUDGET_EXCEEDED","","");resolvedGaps.put(encoded(gap),gap);
        }
        return new Content(BUILDER_VERSION,Source.REGISTRY_VERSION,resolvedNodes,resolvedEdges,List.copyOf(resolvedGaps.values()));
    }
    public byte[] canonicalBytes() { return canonical.canonicalize(content()); }
    public String digest() { return HexFormat.of().formatHex(Digests.sha256(canonicalBytes())); }
}
