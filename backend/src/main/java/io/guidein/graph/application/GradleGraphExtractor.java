package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.*;
import java.util.*;
import java.util.regex.Pattern;

/** Deliberately small, whole-statement recognizer, not a Groovy/Kotlin evaluator. */
public final class GradleGraphExtractor implements GraphExtractor {
    private static final Pattern INCLUDE = Pattern.compile("include\\s*(?:\\((.*)\\)|(.*))");
    private static final Pattern LITERALS = Pattern.compile("[\"'](:[A-Za-z0-9_.:-]+)[\"']");
    private static final Pattern DEPENDENCY = Pattern.compile("(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\\s*\\(\\s*(?:project\\(\\s*[\"'](:[A-Za-z0-9_.:-]+)[\"']\\s*\\)|[\"']([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\\-]+)[\"'])\\s*\\)");
    @Override public String version() { return "gradle-static-v1"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.GRADLE);
        var scripts = new TreeMap<String, String>();
        for (String path : material.paths()) if (path.endsWith(".gradle") || path.endsWith(".gradle.kts")) {
            try {
                scripts.put(path, material.text(path, limits.manifestBytes()));
                String directory = SourcePaths.directory(path);
                facts.node("module:" + directory, NodeType.MODULE, directory, path, Map.of("ecosystem", "gradle"));
            } catch (IllegalArgumentException ex) { facts.gap("GRADLE_INVALID", path, "document"); }
        }
        for (var script : scripts.entrySet()) {
            String path = script.getKey(); String directory = SourcePaths.directory(path);
            String text = script.getValue();
            // Comments and interpolation require a proper lexer; decline rather than extract from strings/comments.
            if (text.contains("/*") || text.contains("//") || text.contains("${") || text.contains("\"\"\"")) {
                facts.gap("GRADLE_DYNAMIC_CONFIGURATION", path, "unsupported-lexical-form"); continue;
            }
            if (!safeStructure(text)) { facts.gap("GRADLE_DYNAMIC_CONFIGURATION", path, "unsupported-control-flow"); continue; }
            boolean dependencyBlock = false;
            int lineNumber = 0;
            for (String line : text.split("\\R")) {
                lineNumber++; String statement = line.strip(); String locator = "line:" + lineNumber;
                if (statement.isEmpty()) continue;
                if (statement.equals("dependencies {")) { dependencyBlock = true; continue; }
                if (dependencyBlock && statement.equals("}")) { dependencyBlock = false; continue; }
                if (statement.startsWith("dependencies {") && statement.endsWith("}")) statement = statement.substring(14, statement.length() - 1).strip();
                var include = INCLUDE.matcher(statement);
                if (include.matches() && path.substring(path.lastIndexOf('/') + 1).startsWith("settings.gradle")) {
                    String arguments = include.group(1) == null ? include.group(2) : include.group(1);
                    var matcher = LITERALS.matcher(arguments); var modules = new ArrayList<String>(); int end = 0; boolean valid = true;
                    while (matcher.find()) {
                        String between = arguments.substring(end, matcher.start()).strip();
                        if (!(between.isEmpty() || between.equals(","))) valid = false;
                        modules.add(matcher.group(1)); end = matcher.end();
                    }
                    if (!valid || modules.isEmpty() || !arguments.substring(end).isBlank()) { facts.gap("GRADLE_DYNAMIC_CONFIGURATION", path, locator); continue; }
                    for (String module : modules) {
                        String target = modulePath(directory, module);
                        facts.node("module:" + target, NodeType.MODULE, module, path, Map.of("ecosystem", "gradle"));
                        facts.edge("module:" + directory, EdgeType.CONTAINS, "module:" + target, facts.evidence(Source.GRADLE, material, path, locator + ":" + module, "STATIC_INCLUDE"));
                    }
                    continue;
                }
                var dependency = DEPENDENCY.matcher(statement);
                if (dependency.matches()) {
                    String target;
                    if (dependency.group(2) != null) {
                        target = "module:" + modulePath(".", dependency.group(2));
                        if (!facts.hasNode(target)) { facts.gap("GRADLE_PROJECT_UNRESOLVED", path, locator); continue; }
                    } else {
                        String[] coordinate = dependency.group(3).split(":");
                        target = "external:maven:" + coordinate[0] + ":" + coordinate[1];
                        facts.node(target, NodeType.EXTERNAL_PROVIDER, coordinate[0] + ":" + coordinate[1], "", Map.of("ecosystem", "maven"));
                    }
                    facts.edge("module:" + directory, EdgeType.DEPENDS_ON, target, facts.evidence(Source.GRADLE, material, path, locator, "STATIC_DEPENDENCY:" + dependency.group(1)));
                } else if (!statement.matches("plugins\\s*\\{\\s*(java|`java-library`)\\s*}")
                        && !statement.matches("rootProject\\.name\\s*=\\s*[\"'][A-Za-z0-9_.-]+[\"']")) {
                    facts.gap("GRADLE_DYNAMIC_CONFIGURATION", path, locator);
                }
            }
        }
    }
    private String modulePath(String root, String value) {
        return SourcePaths.normalize((root.equals(".") ? "" : root + "/") + value.substring(1).replace(':', '/'));
    }
    private boolean safeStructure(String text) {
        boolean block = false;
        for (String line : text.split("\\R")) {
            String statement = line.strip(); if (statement.isEmpty()) continue;
            if (statement.equals("dependencies {")) { if (block) return false; block = true; continue; }
            if (statement.equals("}")) { if (!block) return false; block = false; continue; }
            if (statement.startsWith("dependencies {") && statement.endsWith("}")) {
                if (block) return false;
                if (!DEPENDENCY.matcher(statement.substring(14, statement.length() - 1).strip()).matches()) return false;
            } else if (block) { if (!DEPENDENCY.matcher(statement).matches()) return false; }
            else if (!INCLUDE.matcher(statement).matches()
                    && !statement.matches("plugins\\s*\\{\\s*(java|`java-library`)\\s*}")
                    && !statement.matches("rootProject\\.name\\s*=\\s*[\"'][A-Za-z0-9_.-]+[\"']")) return false;
        }
        return !block;
    }
}
