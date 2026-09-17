package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

public final class DeploymentGraphExtractor implements GraphExtractor {
    private record Resource(String path, int document, JsonNode json, String kind, String namespace, String name) {}
    @Override public String version() { return "deployment-v1"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.DEPLOYMENT);
        var parser = new BoundedDocuments(limits); var resources = new ArrayList<Resource>();
        for (String path : material.paths()) {
            if (path.equals("Dockerfile") || path.endsWith("/Dockerfile")) { dockerfile(material, limits, facts, path); continue; }
            if (!(path.endsWith(".yaml") || path.endsWith(".yml"))) continue;
            if (path.endsWith("guidein.yaml") || path.endsWith("guidein.yml") || path.endsWith("compose.yaml") || path.endsWith("compose.yml") || path.endsWith("openapi.yaml") || path.endsWith("openapi.yml")) continue;
            try {
                int index = 0;
                for (JsonNode doc : parser.yaml(material, path, limits.manifestBytes())) {
                    index++;
                    if (!doc.isObject() || !doc.has("apiVersion") || !doc.has("kind")) continue;
                    String kind = doc.path("kind").asText();
                    if (!Set.of("Deployment", "StatefulSet", "Service").contains(kind)) { facts.gap("DEPLOYMENT_KIND_UNSUPPORTED", path, "document:" + index); continue; }
                    String name = doc.path("metadata").path("name").asText("");
                    String namespace = doc.path("metadata").path("namespace").asText("default");
                    if (name.isBlank() || namespace.isBlank()) throw new IllegalArgumentException();
                    resources.add(new Resource(path, index, doc, kind, namespace, name));
                }
            } catch (RuntimeException ex) { facts.gap("DEPLOYMENT_INVALID", path, "document"); }
        }
        Map<String, Long> counts = new TreeMap<>(); resources.forEach(r -> counts.merge(key(r), 1L, Long::sum));
        for (Resource r : resources) {
            if (counts.get(key(r)) != 1) { facts.gap("DEPLOYMENT_IDENTITY_CONFLICT", r.path, "document:" + r.document); continue; }
            facts.node(key(r), r.kind.equals("Service") ? NodeType.SERVICE : NodeType.DEPLOYMENT, r.name, r.path,
                    Map.of("kind", r.kind, "namespace", r.namespace));
            facts.edge("file:" + r.path, EdgeType.DECLARES, key(r), facts.evidence(Source.DEPLOYMENT, material, r.path, "document:" + r.document, "RESOURCE_DECLARATION"));
        }
        for (Resource service : resources) {
            if (!service.kind.equals("Service") || counts.get(key(service)) != 1) continue;
            JsonNode selector = service.json.path("spec").path("selector");
            if (!selector.isObject() || selector.isEmpty()) { facts.gap("DEPLOYMENT_SELECTOR_UNRESOLVED", service.path, "document:" + service.document); continue; }
            for (Resource deployment : resources) {
                if (deployment.kind.equals("Service") || !deployment.namespace.equals(service.namespace) || counts.get(key(deployment)) != 1) continue;
                JsonNode labels = deployment.json.path("spec").path("template").path("metadata").path("labels");
                boolean match = labels.isObject();
                for (String field : selector.propertyNames()) if (!selector.path(field).isString() || !selector.path(field).equals(labels.path(field))) match = false;
                if (match) {
                    facts.edge(key(deployment), EdgeType.DEPLOYED_AS, key(service), facts.evidence(Source.DEPLOYMENT, material, service.path,
                            "document:" + service.document + "/spec/selector", "EXACT_LABEL_SELECTOR"));
                    facts.edge(key(deployment), EdgeType.DEPLOYED_AS, key(service), facts.evidence(Source.DEPLOYMENT, material, deployment.path,
                            "document:" + deployment.document + "/spec/template/metadata/labels", "POD_TEMPLATE_LABELS"));
                }
            }
        }
    }
    private String key(Resource r) { return r.kind.equals("Service") ? "service:kubernetes:" + r.namespace + ":" + r.name : "deployment:" + r.namespace + ":" + r.kind + ":" + r.name; }
    private void dockerfile(SourceMaterial material, GraphLimits limits, GraphFacts facts, String path) {
        try {
            String key = "deployment:dockerfile:" + path;
            facts.node(key, NodeType.DEPLOYMENT, path, path, Map.of("format", "Dockerfile"));
            facts.edge("file:" + path, EdgeType.DECLARES, key, facts.evidence(Source.DEPLOYMENT, material, path, "document", "DOCKERFILE_DECLARATION"));
            int line = 0;
            for (String statement : material.text(path, limits.manifestBytes()).split("\\R")) {
                line++; String value = statement.strip(); if (value.isEmpty() || value.startsWith("#")) continue;
                String operation = value.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                // Record supported directive presence, never environment values or executable bodies.
                if (Set.of("FROM", "EXPOSE", "COPY").contains(operation)) {
                    facts.node("configuration:" + path + ":" + line, NodeType.CONFIGURATION, operation, path, Map.of("directive", operation));
                    facts.edge(key, EdgeType.DECLARES, "configuration:" + path + ":" + line,
                            facts.evidence(Source.DEPLOYMENT, material, path, "line:" + line, "DOCKERFILE_" + operation));
                } else if (Set.of("RUN", "ADD", "ONBUILD").contains(operation)) facts.gap("DOCKERFILE_DYNAMIC_TOPOLOGY", path, "line:" + line);
            }
        } catch (RuntimeException ex) { facts.gap("DEPLOYMENT_INVALID", path, "document"); }
    }
}
