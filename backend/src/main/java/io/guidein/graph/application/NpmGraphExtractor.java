package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

public final class NpmGraphExtractor implements GraphExtractor {
    private record Package(String path, String name, JsonNode json) { String key() { return "module:" + SourcePaths.directory(path); } }
    @Override public String version() { return "npm-v1"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.NPM);
        var parser = new BoundedDocuments(limits);
        Map<String, Package> packages = new TreeMap<>();
        for (String path : material.paths()) {
            if (!(path.equals("package.json") || path.endsWith("/package.json"))) continue;
            try {
                JsonNode node = parser.json(material, path, limits.manifestBytes());
                if (!node.isObject()) throw new IllegalArgumentException();
                String name = node.path("name").asText("");
                if (name.isBlank()) facts.gap("NPM_NAME_UNAVAILABLE", path, "/name");
                Package pkg = new Package(path, name, node); packages.put(path, pkg);
                facts.node(pkg.key(), NodeType.MODULE, name.isBlank() ? SourcePaths.directory(path) : name, path,
                        Map.of("ecosystem", "npm", "name", name, "version", node.path("version").asText("")));
                if (!pkg.key().equals("module:.")) facts.edge("repo:root", EdgeType.CONTAINS, pkg.key(),
                        facts.evidence(Source.NPM, material, path, "/name", "PACKAGE_DECLARATION"));
            } catch (RuntimeException ex) { facts.gap("NPM_INVALID", path, "document"); }
        }
        Map<String, Set<String>> workspaceGroups = new TreeMap<>();
        for (Package root : packages.values()) {
            JsonNode workspaces = root.json.path("workspaces");
            if (workspaces.isMissingNode()) continue;
            if (workspaces.isObject()) workspaces = workspaces.path("packages");
            if (!workspaces.isArray()) { facts.gap("NPM_WORKSPACE_UNRESOLVED", root.path, "/workspaces"); continue; }
            var members = new TreeSet<String>(); members.add(root.path);
            for (JsonNode item : workspaces) {
                String pattern = item.asText("");
                try {
                    SourcePaths.normalize(pattern);
                    if (pattern.contains("**") || pattern.contains("!") || pattern.contains("{") || pattern.contains("[")) throw new IllegalArgumentException();
                    String base = SourcePaths.directory(root.path);
                    String prefix = base.equals(".") ? "" : base + "/";
                    String regex = "^" + java.util.regex.Pattern.quote(prefix + pattern).replace("*", "\\E[^/]+\\Q") + "/package\\.json$";
                    for (Package candidate : packages.values()) if (candidate.path.matches(regex)) members.add(candidate.path);
                } catch (IllegalArgumentException ex) { facts.gap("NPM_WORKSPACE_UNRESOLVED", root.path, "/workspaces"); }
            }
            for (String member : members) workspaceGroups.computeIfAbsent(member, ignored -> new TreeSet<>()).addAll(members);
        }
        for (Package pkg : packages.values()) {
            for (String category : List.of("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")) {
                JsonNode dependencies = pkg.json.path(category);
                if (dependencies.isMissingNode()) continue;
                if (!dependencies.isObject()) { facts.gap("NPM_INVALID", pkg.path, "/" + category); continue; }
                for (String name : new TreeSet<>(dependencies.propertyNames())) {
                    String spec = dependencies.path(name).asText(""); String locator = "/" + category + "/" + name.replace("~", "~0").replace("/", "~1");
                    List<Package> matches = packages.values().stream().filter(p -> !name.isBlank() && p.name.equals(name)
                            && workspaceGroups.getOrDefault(pkg.path, Set.of()).contains(p.path)).toList();
                    if (matches.size() > 1) { facts.gap("NPM_AMBIGUOUS_WORKSPACE", pkg.path, locator); continue; }
                    String target;
                    if (matches.size() == 1 && (spec.startsWith("workspace:") || spec.equals(matches.getFirst().json.path("version").asText()))) target = matches.getFirst().key();
                    else if (spec.startsWith("workspace:") || spec.startsWith("file:") || spec.startsWith("link:")) {
                        facts.gap("NPM_WORKSPACE_UNRESOLVED", pkg.path, locator); continue;
                    } else {
                        target = "external:npm:" + name;
                        facts.node(target, NodeType.EXTERNAL_PROVIDER, name, "", Map.of("ecosystem", "npm"));
                        if (matches.size() == 1) facts.gap("NPM_WORKSPACE_VERSION_UNRESOLVED", pkg.path, locator);
                    }
                    var ev = facts.evidence(Source.NPM, material, pkg.path, locator, "DECLARED_DEPENDENCY");
                    facts.edge(pkg.key(), EdgeType.DEPENDS_ON, target, new Evidence(ev.source(), ev.path(), ev.locator(), ev.digest(), ev.extractorVersion(), ev.observation(), ev.trust(), ev.confidence(), Map.of("category", category)));
                }
            }
            String directory = SourcePaths.directory(pkg.path); String lockPath = (directory.equals(".") ? "" : directory + "/") + "package-lock.json";
            if (material.contains(lockPath)) try {
                JsonNode lock = parser.json(material, lockPath, limits.manifestBytes());
                if (!Set.of(1, 2, 3).contains(lock.path("lockfileVersion").asInt())) facts.gap("NPM_LOCK_VERSION_UNSUPPORTED", lockPath, "/lockfileVersion");
            } catch (RuntimeException ex) { facts.gap("NPM_INVALID", lockPath, "document"); }
        }
    }
}
