package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import java.util.Map;

public final class FileTreeExtractor implements GraphExtractor {
    @Override public String version() { return "file-tree-v1"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.FILE_TREE);
        facts.node("repo:root", NodeType.REPOSITORY, "Repository", "", Map.of());
        facts.node("module:.", NodeType.MODULE, ".", "", Map.of());
        var paths = material.paths();
        for (String path : paths) {
            var evidence = facts.evidence(Source.FILE_TREE, material, path, "tree", "TRACKED_FILE");
            if (path.equals(paths.getFirst())) facts.edge("repo:root", EdgeType.CONTAINS, "module:.", evidence);
            facts.node("file:" + path, NodeType.FILE, path, path, Map.of("digest", evidence.digest()));
            facts.edge("module:.", EdgeType.CONTAINS, "file:" + path, evidence);
        }
        material.gaps().forEach(g -> facts.gap(g.category(), g.path(), g.locator()));
    }
}
