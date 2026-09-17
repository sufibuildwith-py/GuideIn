package io.guidein.evaluation;

import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Offline parser comparison; this project is never a control-plane dependency. */
class ParserBenchmarkTest {
    record Facts(boolean valid, String pkg, List<String> types, List<String> imports) {}
    interface Parser { Facts parse(String source); }
    @Test void compare() throws Exception {
        Path corpus=Path.of("../../fixtures/graph");
        List<Path> files;
        try(var paths=Files.walk(corpus)) {files=paths.filter(p->p.toString().endsWith(".java")).sorted().toList();}
        assertTrue(files.size()>=8,"Java corpus must exist before benchmarking");
        List<String> source=new ArrayList<>();for(var p:files)source.add(Files.readString(p));
        var config=new com.github.javaparser.ParserConfiguration().setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
        var jp=new com.github.javaparser.JavaParser(config);
        Parser javaParser=text->{
            var result=jp.parse(text);if(!result.isSuccessful() || result.getResult().isEmpty())return new Facts(false,"",List.of(),List.of());
            var cu=result.getResult().get();return new Facts(true,cu.getPackageDeclaration().map(p->p.getNameAsString()).orElse(""),
                    cu.getTypes().stream().map(t->t.getNameAsString()).toList(),cu.getImports().stream().map(i->(i.isStatic()?"static ":"")+i.getNameAsString()+(i.isAsterisk()?".*":"")).toList());
        };
        var errors=new ArrayList<Throwable>();
        var ctx=new org.openrewrite.InMemoryExecutionContext(errors::add);
        var rewrite=org.openrewrite.java.JavaParser.fromJavaVersion().build();
        Parser openRewrite=text->{
            rewrite.reset();var trees=rewrite.parse(ctx,text).toList();
            if(trees.size()!=1 || !(trees.getFirst() instanceof org.openrewrite.java.tree.J.CompilationUnit cu))return new Facts(false,"",List.of(),List.of());
            return new Facts(true,cu.getPackageDeclaration()==null?"":cu.getPackageDeclaration().getExpression().printTrimmed(),
                    cu.getClasses().stream().map(t->t.getSimpleName()).toList(),cu.getImports().stream().map(i->(i.isStatic()?"static ":"")+i.getQualid().printTrimmed()).toList());
        };
        // Hand-labeled fixture facts: neither parser supplies the other's oracle.
        Map<String,Facts> labels=Map.of(
            "07-java-packages-imports/source/src/main/java/demo/App.java",new Facts(true,"demo",List.of("App"),List.of("demo.core.Core","java.util.*")),
            "07-java-packages-imports/source/src/main/java/demo/core/Core.java",new Facts(true,"demo.core",List.of("Core"),List.of()),
            "08-duplicate-java-simple-names/source/src/foo/a/User.java",new Facts(true,"foo.a",List.of("User"),List.of()),
            "08-duplicate-java-simple-names/source/src/foo/b/User.java",new Facts(true,"foo.b",List.of("User"),List.of()),
            "08-duplicate-java-simple-names/source/src/Main.java",new Facts(true,"",List.of("Main"),List.of("foo.a.User")),
            "15-mixed-stack-monorepo/source/src/demo/App.java",new Facts(true,"demo",List.of("App"),List.of()),
            "16-malformed-source/source/Broken.java",new Facts(false,"",List.of(),List.of()),
            "16-malformed-source/source/src/Good.java",new Facts(true,"",List.of("Good"),List.of()),
            "19-symbol-resolution-partial/source/src/Partial.java",new Facts(true,"",List.of("Partial"),List.of("missing.api.Client","static missing.Util.run")));
        var expected=files.stream().map(p->Objects.requireNonNull(labels.get(corpus.relativize(p).toString().replace('\\','/')),"unlabeled source")).toList();
        // Explicit labels independent of the competing parser: exact qualified import and duplicate simple names.
        int main=files.indexOf(corpus.resolve("08-duplicate-java-simple-names/source/src/Main.java"));
        assertTrue(main>=0);assertEquals(List.of("foo.a.User"),expected.get(main).imports());
        int malformed=files.indexOf(corpus.resolve("16-malformed-source/source/Broken.java"));assertFalse(expected.get(malformed).valid());
        List<String> results=new ArrayList<>();
        for(var entry:Map.of("javaparser-3.28.2",javaParser,"openrewrite-8.87.7",openRewrite).entrySet()) {
            var parser=entry.getValue();for(String text:source)parser.parse(text);
            System.gc();long heapBefore=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            for(var pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();
            long started=System.nanoTime();int equal=0,valid=0;
            for(int round=0;round<20;round++)for(int i=0;i<source.size();i++) {
                Facts actual=parser.parse(source.get(i));if(actual.equals(expected.get(i)))equal++;if(actual.valid())valid++;
            }
            long duration=System.nanoTime()-started,heapAfter=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long peak=ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();
            results.add("{\"parser\":\""+entry.getKey()+"\",\"inputs\":"+source.size()+",\"rounds\":20,\"matching_results\":"+equal+",\"valid_parses\":"+valid+",\"duration_ms\":"+(duration/1e6)+",\"heap_before_bytes\":"+heapBefore+",\"heap_after_bytes\":"+heapAfter+",\"sum_pool_peak_bytes\":"+peak+"}");
        }
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/results.json"),"{\"scope\":\"same JVM; heap observations are approximate, source parsing only, no repository classpath or builds\",\"results\":["+String.join(",",results)+"]}");
    }
    @Test void compareResolutionLimits() throws Exception {
        Path sourceRoot=Path.of("../../fixtures/graph/07-java-packages-imports/source/src/main/java");
        var solver=new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver(
                new com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver(sourceRoot),
                new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver(true));
        var parser=new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new com.github.javaparser.symbolsolver.JavaSymbolSolver(solver)));
        var app=parser.parse(sourceRoot.resolve("demo/App.java")).getResult().orElseThrow();
        assertEquals("demo.core.Core",app.findFirst(com.github.javaparser.ast.body.VariableDeclarator.class).orElseThrow().resolve().getType().describe());
        var missing=parser.parse("import absent.Client; class Missing { Client field; }").getResult().orElseThrow();
        assertThrows(com.github.javaparser.resolution.UnsolvedSymbolException.class,()->missing.findFirst(com.github.javaparser.ast.body.VariableDeclarator.class).orElseThrow().resolve().getType());
        var rewrite=org.openrewrite.java.JavaParser.fromJavaVersion().build();
        var context=new org.openrewrite.InMemoryExecutionContext(error->{});
        var trees=rewrite.parse(context,Files.readString(sourceRoot.resolve("demo/App.java")),Files.readString(sourceRoot.resolve("demo/core/Core.java"))).toList();
        var observed=new ArrayList<String>();
        var visitor=new org.openrewrite.java.JavaIsoVisitor<List<String>>() {
            @Override public org.openrewrite.java.tree.J.VariableDeclarations.NamedVariable visitVariable(org.openrewrite.java.tree.J.VariableDeclarations.NamedVariable v,List<String> names) {
                if(v.getSimpleName().equals("core"))names.add(String.valueOf(v.getType()));
                return super.visitVariable(v,names);
            }
        };
        for(var tree:trees)visitor.visit(tree,observed);
        assertEquals(List.of("demo.core.Core"),observed);
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/resolution.json"),"{\"javaparser_local_source_resolution\":true,\"javaparser_missing_classpath_rejected\":true,\"openrewrite_local_source_resolution\":true,\"repository_code_executed\":false,\"production_decision\":\"Core AST plus exact repository declaration index; missing imports remain gaps. General symbol solver is not required for V1.\"}");
    }
}
