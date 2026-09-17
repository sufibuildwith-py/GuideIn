package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

public final class OpenApiGraphExtractor implements GraphExtractor {
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private record Visit(String path, String pointer) {}
    @Override public String version() { return "openapi-v1"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.OPENAPI);
        var parser = new BoundedDocuments(limits);
        for (String path : material.paths()) {
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (!Set.of("openapi.json", "openapi.yaml", "openapi.yml").contains(filename)) continue;
            try {
                JsonNode document = parser.document(material, path, limits.openApiBytes());
                if (!document.isObject() || !document.path("openapi").asText("").startsWith("3.") || !document.path("paths").isObject()) throw new IllegalArgumentException();
                String api = "api:" + path;
                facts.node(api, NodeType.API, document.path("info").path("title").asText(path), path, Map.of("version", document.path("info").path("version").asText("")));
                facts.edge("file:" + path, EdgeType.DECLARES, api, facts.evidence(Source.OPENAPI, material, path, "", "API_DECLARATION"));
                for (String endpointPath : new TreeSet<>(document.path("paths").propertyNames())) {
                    JsonNode operations = document.path("paths").path(endpointPath);
                    if (!endpointPath.startsWith("/") || !operations.isObject()) { facts.gap("OPENAPI_INVALID_PATH", path, "/paths"); continue; }
                    for (String method : new TreeSet<>(operations.propertyNames())) if (METHODS.contains(method)) {
                        if (!operations.path(method).isObject()) { facts.gap("OPENAPI_INVALID_OPERATION", path, "/paths"); continue; }
                        String key = "endpoint:" + path + ":" + method.toUpperCase(Locale.ROOT) + ":" + endpointPath;
                        facts.node(key, NodeType.ENDPOINT, method.toUpperCase(Locale.ROOT) + " " + endpointPath, path, Map.of("method", method.toUpperCase(Locale.ROOT), "path", endpointPath));
                        facts.edge(api, EdgeType.CONTAINS, key, facts.evidence(Source.OPENAPI, material, path, "/paths/" + escape(endpointPath) + "/" + method, "OPERATION_DECLARATION"));
                    }
                }
                checkReferences(material, parser, path, document, limits, facts);
            } catch (RuntimeException ex) { facts.gap("OPENAPI_INVALID", path, "document"); }
        }
    }
    private void checkReferences(SourceMaterial material, BoundedDocuments parser, String root, JsonNode document, GraphLimits limits, GraphFacts facts) {
        Map<String, JsonNode> documents = new TreeMap<>(); documents.put(root, document);
        ArrayDeque<Visit> queue = new ArrayDeque<>(); queue.add(new Visit(root, "")); Set<Visit> seen = new HashSet<>(); int inspected = 0;
        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst(); if (!seen.add(visit)) continue;
            if (++inspected > 10_000) { facts.gap("OPENAPI_REFERENCE_BUDGET", root, ""); return; }
            JsonNode node = documents.get(visit.path).at(visit.pointer);
            if (node.isObject()) {
                for (String field : new TreeSet<>(node.propertyNames())) {
                    if (!field.equals("$ref")) { queue.add(new Visit(visit.path, visit.pointer + "/" + escape(field))); continue; }
                    String ref = node.path(field).asText(""); int hash = ref.indexOf('#');
                    String file = hash < 0 ? ref : ref.substring(0, hash); String pointer = hash < 0 ? "" : ref.substring(hash + 1);
                    try {
                        if (ref.isEmpty() || ref.contains(":") || file.startsWith("/") || file.contains("\\") || file.contains("%") || (!pointer.isEmpty() && !pointer.startsWith("/"))) throw new IllegalArgumentException();
                        // URI dot segments are legal within the immutable repository map; parent traversal stays forbidden.
                        if (!file.isEmpty()) file = String.join("/", Arrays.stream(file.split("/", -1)).filter(segment -> !segment.equals(".")).toList());
                        String path = file.isEmpty() ? visit.path : SourcePaths.normalize((SourcePaths.directory(visit.path).equals(".") ? "" : SourcePaths.directory(visit.path) + "/") + file);
                        if (!material.contains(path)) { facts.gap("OPENAPI_REF_UNRESOLVED", visit.path, visit.pointer); continue; }
                        if (!documents.containsKey(path)) documents.put(path, parser.document(material, path, limits.openApiBytes()));
                        if (documents.get(path).at(pointer).isMissingNode()) { facts.gap("OPENAPI_REF_UNRESOLVED", visit.path, visit.pointer); continue; }
                        queue.add(new Visit(path, pointer));
                    } catch (RuntimeException ex) { facts.gap("OPENAPI_REF_BLOCKED", visit.path, visit.pointer); }
                }
            } else if (node.isArray()) for (int i = 0; i < node.size(); i++) queue.add(new Visit(visit.path, visit.pointer + "/" + i));
        }
    }
    private static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
}
