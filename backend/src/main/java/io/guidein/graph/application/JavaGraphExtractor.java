package io.guidein.graph.application;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.guidein.graph.api.GraphModel.*;
import java.util.*;

/** AST facts only. No classpath downloads, reflection, compilation or invocation. */
public final class JavaGraphExtractor implements GraphExtractor {
    @Override public String version() { return "java-v1:javaparser-3.28.2"; }
    @Override public void extract(SourceMaterial material, GraphLimits limits, GraphFacts facts) {
        facts.source(Source.JAVA);
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        Map<String, CompilationUnit> units = new TreeMap<>();
        Map<String, List<String>> declarations = new TreeMap<>();
        for (String path : material.paths()) {
            facts.checkpoint();
            if (!path.endsWith(".java")) continue;
            try {
                String source = material.text(path, limits.javaBytes());
                if (!JavaSyntaxBudget.within(source, limits.nesting())) { facts.gap("JAVA_PARSE_LIMIT", path, "nesting"); continue; }
                var result = parser.parse(source);
                if (!result.isSuccessful() || result.getResult().isEmpty()) { facts.gap("JAVA_PARSE_FAILED", path, "syntax"); continue; }
                var unit = result.getResult().orElseThrow(); units.put(path, unit);
                String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                for (var type : unit.getTypes()) {
                    String qualified = pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
                    declarations.computeIfAbsent(qualified, ignored -> new ArrayList<>()).add(path);
                }
            } catch (IllegalArgumentException ex) { facts.gap("INVALID_UNICODE".equals(ex.getMessage()) ? "INVALID_UNICODE" : "JAVA_PARSE_LIMIT", path, ""); }
            catch (StackOverflowError exhaustedParserStack) { facts.gap("JAVA_PARSE_LIMIT", path, "parser-stack"); }
        }
        for (var entry : units.entrySet()) {
            String path = entry.getKey(); var unit = entry.getValue();
            String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            String packageKey = "package:" + pkg;
            facts.node(packageKey, NodeType.PACKAGE, pkg, "", Map.of());
            var packageEvidence = facts.evidence(Source.JAVA, material, path, "package", "PACKAGE_DECLARATION");
            facts.edge("module:.", EdgeType.CONTAINS, packageKey, packageEvidence);
            for (var type : unit.getTypes()) {
                String qualified = pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString();
                if (declarations.get(qualified).size() != 1) { facts.gap("JAVA_AMBIGUOUS_TYPE", path, qualified); continue; }
                String key = "java-type:" + qualified;
                facts.node(key, NodeType.SYMBOL, qualified, path, Map.of("kind", type.getClass().getSimpleName()));
                var evidence = facts.evidence(Source.JAVA, material, path, "line:" + type.getBegin().orElseThrow().line + ":column:" + type.getBegin().orElseThrow().column, "TYPE_DECLARATION");
                facts.edge("file:" + path, EdgeType.DECLARES, key, evidence);
                facts.edge(packageKey, EdgeType.CONTAINS, key, evidence);
            }
        }
        for (var entry : units.entrySet()) {
            String path = entry.getKey();
            for (var imp : entry.getValue().getImports()) {
                String name = imp.getNameAsString();
                String locator = "line:" + imp.getBegin().orElseThrow().line + ":column:" + imp.getBegin().orElseThrow().column;
                if (imp.isStatic()) {
                    // Static member attribution requires a declared member model; do not manufacture it.
                    facts.gap("UNRESOLVED_SYMBOL", path, locator); continue;
                }
                String target;
                if (imp.isAsterisk()) {
                    target = "package:" + name;
                    facts.node(target, NodeType.PACKAGE, name, "", Map.of());
                } else {
                    target = "java-type:" + name;
                    if (declarations.getOrDefault(name, List.of()).size() != 1 || !facts.hasNode(target)) {
                        facts.gap("UNRESOLVED_SYMBOL", path, locator); continue;
                    }
                }
                facts.edge("file:" + path, EdgeType.IMPORTS, target,
                        facts.evidence(Source.JAVA, material, path, locator, imp.isAsterisk() ? "PACKAGE_IMPORT" : "EXACT_IMPORT"));
            }
        }
    }
}
