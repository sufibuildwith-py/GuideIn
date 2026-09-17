package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

public final class GuideInConfigExtractor implements GraphExtractor {
    private static final Set<String> CRITICALITY = Set.of("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    @Override public String version() { return "guidein-config-v1"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.GUIDEIN);
        for (String path : List.of("guidein.yaml", "guidein.yml", ".guidein/guidein.yaml")) {
            if (!material.contains(path)) continue;
            try {
                JsonNode root = new BoundedDocuments(limits).document(material, path, limits.manifestBytes());
                keys(root, Set.of("guidein")); JsonNode config = root.path("guidein");
                keys(config, Set.of("version", "repository", "components", "owners"));
                if (config.path("version").asInt() != 1) throw new IllegalArgumentException();
                if (config.has("repository")) { keys(config.path("repository"), Set.of("criticality")); criticality(config.path("repository")); }
                var components = config.path("components");
                if (!components.isMissingNode() && !components.isArray()) throw new IllegalArgumentException();
                var names = new HashSet<String>();
                for (var component : components) {
                    keys(component, Set.of("name", "paths", "criticality"));
                    if (!component.path("name").isString() || component.path("name").asText().isBlank()
                            || !names.add(component.path("name").asText()) || !component.path("paths").isArray()) throw new IllegalArgumentException();
                    criticality(component);
                    for (var pattern : component.path("paths")) validatePattern(pattern.asText(""));
                }
                var owners = config.path("owners");
                if (!owners.isMissingNode() && !owners.isArray()) throw new IllegalArgumentException();
                for (var owner : owners) {
                    keys(owner, Set.of("domain", "team"));
                    if (!owner.path("domain").isString() || !owner.path("team").isString() || owner.path("domain").asText().isBlank() || owner.path("team").asText().isBlank()) throw new IllegalArgumentException();
                }
                // No facts are emitted until the entire safety-relevant configuration validates.
                if (config.has("repository")) facts.criticality("repo:root", criticality(config.path("repository")), path);
                for (var component : components) {
                    String name = component.path("name").asText(); String key = "module:guidein:" + name;
                    facts.node(key, NodeType.MODULE, name, path, Map.of("definition", "GUIDEIN_EXPLICIT"));
                    facts.criticality(key, criticality(component), path);
                    facts.edge("repo:root", EdgeType.CONTAINS, key, facts.evidence(Source.GUIDEIN, material, path, "/guidein/components/" + name, "COMPONENT_DECLARATION"));
                    for (var pattern : component.path("paths")) for (String candidate : material.paths()) {
                        if (matches(pattern.asText(), candidate)) facts.edge(key, EdgeType.CONTAINS, "file:" + candidate,
                                facts.evidence(Source.GUIDEIN, material, path, "/guidein/components/" + name + "/paths/" + pattern.asText(), "EXPLICIT_PATH_MEMBERSHIP"));
                    }
                }
                for (var owner : owners) {
                    String key = "configuration:owner:" + owner.path("domain").asText();
                    facts.node(key, NodeType.CONFIGURATION, owner.path("domain").asText(), path,
                            Map.of("domain", owner.path("domain").asText(), "team", owner.path("team").asText()));
                    facts.edge("file:" + path, EdgeType.DECLARES, key, facts.evidence(Source.GUIDEIN, material, path, "/guidein/owners/" + owner.path("domain").asText(), "OWNER_DECLARATION"));
                }
            } catch (RuntimeException ex) { facts.gap("GUIDEIN_CONFIG_INVALID", path, "document"); }
        }
    }
    private void keys(JsonNode node, Set<String> allowed) {
        if (!node.isObject() || !allowed.containsAll(node.propertyNames())) throw new IllegalArgumentException();
    }
    private String criticality(JsonNode node) {
        String value = node.path("criticality").asText("UNKNOWN"); if (!CRITICALITY.contains(value)) throw new IllegalArgumentException(); return value;
    }
    private void validatePattern(String value) {
        if (value.endsWith("/**")) value = value.substring(0, value.length() - 3);
        if (value.contains("*") || value.contains("?") || value.contains("[") || value.contains("{")) throw new IllegalArgumentException();
        SourcePaths.normalize(value);
    }
    private boolean matches(String pattern, String path) {
        return pattern.endsWith("/**") ? path.startsWith(pattern.substring(0, pattern.length() - 2)) : path.equals(pattern);
    }
}
