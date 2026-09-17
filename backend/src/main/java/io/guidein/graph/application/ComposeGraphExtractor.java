package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import java.util.*;

public final class ComposeGraphExtractor implements GraphExtractor {
    @Override public String version() { return "compose-v1"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.COMPOSE);
        var parser = new BoundedDocuments(limits);
        for (String path : material.paths()) {
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (!Set.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml").contains(filename)) continue;
            try {
                var document = parser.document(material, path, limits.manifestBytes());
                var services = document.path("services");
                if (!services.isObject()) throw new IllegalArgumentException();
                for (String name : new TreeSet<>(services.propertyNames())) {
                    if (!services.path(name).isObject()) throw new IllegalArgumentException();
                    facts.node(key(path, name), NodeType.SERVICE, name, path, Map.of("format", "compose"));
                    facts.edge("file:" + path, EdgeType.DECLARES, key(path, name), facts.evidence(Source.COMPOSE, material, path, "/services/" + name, "SERVICE_DECLARATION"));
                }
                for (String name : new TreeSet<>(services.propertyNames())) {
                    var deps = services.path(name).path("depends_on");
                    if (deps.isMissingNode()) continue;
                    var targets = new TreeSet<String>();
                    if (deps.isObject()) targets.addAll(deps.propertyNames());
                    else if (deps.isArray()) for (var dep : deps) { if (!dep.isString()) throw new IllegalArgumentException(); targets.add(dep.asText()); }
                    else throw new IllegalArgumentException();
                    for (String target : targets) {
                        String locator = "/services/" + name + "/depends_on/" + target;
                        if (!services.has(target)) { facts.gap("COMPOSE_DEPENDENCY_UNRESOLVED", path, locator); continue; }
                        facts.edge(key(path, name), EdgeType.DEPENDS_ON, key(path, target), facts.evidence(Source.COMPOSE, material, path, locator, "DEPENDS_ON"));
                    }
                }
            } catch (RuntimeException ex) { facts.gap("COMPOSE_INVALID", path, "document"); }
        }
    }
    private String key(String path, String service) {
        // Retain familiar root keys; nested manifests are separate Compose projects.
        String directory = SourcePaths.directory(path);
        return "service:compose:" + (directory.equals(".") ? "" : directory + ":") + service;
    }
}
