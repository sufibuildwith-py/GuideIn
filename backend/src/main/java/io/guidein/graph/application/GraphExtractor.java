package io.guidein.graph.application;

/** Pure extraction boundary: no database, provider client, shell or filesystem access. */
public interface GraphExtractor {
    String version();
    void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts);
}
