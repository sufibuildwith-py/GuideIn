package io.guidein.graph;

import io.guidein.graph.api.GraphModel.*;
import io.guidein.graph.application.*;
import io.guidein.platform.infrastructure.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class GraphFoundationTest {
    private final GraphLimits limits = GraphLimits.defaults();
    private GraphFacts extract(Map<String, byte[]> input) {
        var material = new SourceMaterial(input, limits);
        var facts = new GraphFacts(limits, new Rfc8785CanonicalJson(JsonMapper.builder().build()));
        new FileTreeExtractor().extract(material, limits, facts);
        new JavaGraphExtractor().extract(material, limits, facts);
        return facts;
    }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    @Test void pathsRejectEscapesAndNormalizeSeparators() {
        for (String path : List.of("../x", "/etc/passwd", "C:\\Windows\\x", "file:///tmp/x", "a/../../x", "a//x", "a/./x", "a\u0000x", "a\uD800x"))
            assertThrows(IllegalArgumentException.class, () -> SourcePaths.normalize(path), path);
        assertEquals("src/main/App.java", SourcePaths.normalize("src\\main\\App.java"));
        assertTrue(SourcePaths.excluded("module/target/a.class"));
        assertFalse(SourcePaths.excluded("src/building.java"));
    }
    @Test void materialIsDefensivelyCopiedAndUtf8FailsClosed() {
        byte[] source = bytes("class App {}");
        var material = new SourceMaterial(Map.of("App.java", source, "bad.java", new byte[]{(byte) 0xC0}), limits);
        source[0] = 0; var copy = material.bytes("App.java"); copy[0] = 1;
        assertEquals("class App {}", material.text("App.java", 100));
        assertThrows(IllegalArgumentException.class, () -> material.text("bad.java", 100));
    }
    @Test void separatorCollisionCannotChooseAnAttackerControlledSource() {
        var material = new SourceMaterial(Map.of("a/b/c.java", bytes("first"), "a\\b/c.java", bytes("second"), "a\\b\\c.java", bytes("third")), limits);
        assertFalse(material.contains("a/b/c.java"));
        assertTrue(material.gaps().stream().anyMatch(g -> g.category().equals("PATH_COLLISION")));
    }
    @Test void exactImportsDoNotMatchSimpleNamesAndMalformedFilesPreserveFacts() {
        var facts = extract(Map.of("a/User.java", bytes("package a; public class User {}"),
                "b/User.java", bytes("package b; public class User {}"), "Main.java", bytes("import a.User; class Main { User user; }"),
                "Broken.java", bytes("class {")));
        assertTrue(facts.content().edges().stream().anyMatch(e -> e.from().equals("file:Main.java") && e.type() == EdgeType.IMPORTS && e.to().equals("java-type:a.User")));
        assertFalse(facts.content().edges().stream().anyMatch(e -> e.from().equals("file:Main.java") && e.type() == EdgeType.IMPORTS && e.to().equals("java-type:b.User")));
        assertEquals("PARTIAL", facts.content().status());
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("JAVA_PARSE_FAILED")));
    }
    @Test void wildcardDoesNotFanOutAndMissingImportsRemainGaps() {
        var facts = extract(Map.of("Main.java", bytes("import foo.*; import missing.Client; import static missing.Util.run; class Main {}")));
        var imports = facts.content().edges().stream().filter(e -> e.type() == EdgeType.IMPORTS).toList();
        assertEquals(1, imports.size()); assertEquals("package:foo", imports.getFirst().to());
        assertEquals(2, facts.content().gaps().stream().filter(g -> g.category().equals("UNRESOLVED_SYMBOL")).count());
    }
    @Test void ambiguousQualifiedDeclarationDoesNotCreateTrustedImport() {
        var facts = extract(Map.of("one/A.java", bytes("package p; class A {}"), "two/A.java", bytes("package p; class A {}"), "B.java", bytes("import p.A; class B {}")));
        assertFalse(facts.content().edges().stream().anyMatch(e -> e.type() == EdgeType.IMPORTS));
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("JAVA_AMBIGUOUS_TYPE")));
    }
    @Test void contentReproducesAcrossOneHundredDiscoveryOrdersAndSeparatorStyles() {
        var input = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 30; i++) input.put("src/T" + i + ".java", bytes("package demo; record T" + i + "(String value) {}"));
        var expected = extract(input).canonicalBytes();
        var keys = new ArrayList<>(input.keySet());
        for (int round = 0; round < 100; round++) {
            Collections.shuffle(keys, new Random(round)); var randomized = new LinkedHashMap<String, byte[]>();
            for (String key : keys) randomized.put(round % 2 == 0 ? key : key.replace('/', '\\'), input.get(key));
            assertArrayEquals(expected, extract(randomized).canonicalBytes(), "round " + round);
        }
    }
    @Test void sourceAndGraphBudgetsMakePartialProduct() {
        var tiny = new GraphLimits(2, 100, 50, 50, 50, 50, 8, 3, 1, 10);
        var material = new SourceMaterial(Map.of("a", bytes("a"), "b", bytes("b"), "c", bytes("c")), tiny);
        var facts = new GraphFacts(tiny, new Rfc8785CanonicalJson(JsonMapper.builder().build()));
        new FileTreeExtractor().extract(material, tiny, facts);
        assertTrue(facts.content().nodes().size() <= 3); assertTrue(facts.content().edges().size() <= 1);
        assertEquals("PARTIAL", facts.content().status());
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("SOURCE_BUDGET_EXCEEDED")));
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("GRAPH_BUDGET_EXCEEDED")));
    }
    @Test void confidenceCannotBeInventedAndDuplicateEvidenceHasOneSemanticEdge() {
        assertThrows(IllegalArgumentException.class, () -> new Evidence(Source.JAVA, "a", "1", "d", "v", "o", Trust.EXPLICIT, "1.00", Map.of()));
        var material = new SourceMaterial(Map.of("a", bytes("a")), limits);
        var facts = extract(Map.of("a", bytes("a")));
        var ev = facts.evidence(Source.FILE_TREE, material, "a", "tree", "TRACKED_FILE");
        for (int i = 0; i < 100; i++) facts.edge("module:.", EdgeType.CONTAINS, "file:a", ev);
        var edges = facts.content().edges().stream().filter(e -> e.to().equals("file:a")).toList();
        assertEquals(1, edges.size()); assertEquals(1, edges.getFirst().evidence().size());
    }
    @Test void deeplyNestedJavaIsPartialWithoutLosingIndependentSource() {
        var facts = extract(Map.of("Deep.java", bytes("class Deep { Object x = " + "(".repeat(2000) + "1" + ")".repeat(2000) + "; }"),
                "Good.java", bytes("record Good(String value) {}")));
        assertTrue(facts.hasNode("java-type:Good"));
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("JAVA_PARSE_LIMIT")));
        assertEquals("PARTIAL", facts.content().status());
    }
    @Test void javaGeneratedFormsAndUnicodeIdentifiersRemainDeterministic() {
        for (int i = 0; i < 100; i++) {
            String source = "package demo; /* {{{ comments */ @Deprecated sealed interface Shape" + i
                    + " permits Circle" + i + " {} final class Circle" + i + " implements Shape" + i
                    + " { record Nested(int café) {} String text = \"(((\"; }";
            var first = extract(Map.of("src/Shape" + i + ".java", bytes(source)));
            assertEquals("READY", first.content().status());
            assertTrue(first.hasNode("java-type:demo.Shape" + i));
            assertArrayEquals(first.canonicalBytes(), extract(Map.of("src/Shape" + i + ".java", bytes(source))).canonicalBytes());
        }
    }
    @Test void hostileOversizedUnicodeAndCardinalityInputsFailClosedWithoutLosingSafeFacts() {
        byte[] oversized=new byte[limits.javaBytes()+1];Arrays.fill(oversized,(byte)'a');
        var javaFacts=extract(Map.of("Good.java",bytes("record Good(int value) {}"),"Broken.java",bytes("class {"),
                "Oversized.java",oversized,"Unicode.java",new byte[]{(byte)0xc0}));
        assertTrue(javaFacts.hasNode("java-type:Good"));
        assertTrue(javaFacts.content().gaps().stream().anyMatch(g->g.category().equals("JAVA_PARSE_FAILED")));
        assertTrue(javaFacts.content().gaps().stream().anyMatch(g->g.category().equals("JAVA_PARSE_LIMIT")));
        assertTrue(javaFacts.content().gaps().stream().anyMatch(g->g.category().equals("INVALID_UNICODE")));
        assertEquals("PARTIAL",javaFacts.content().status());

        var tiny=new GraphLimits(10,2_000_000,1_000_000,1_000_000,1_000_000,1_000_000,64,200,100,100);
        StringBuilder pom=new StringBuilder("<project><groupId>g</groupId><artifactId>root</artifactId><dependencies>");
        for(int i=0;i<2_000;i++)pom.append("<dependency><groupId>g</groupId><artifactId>d").append(i).append("</artifactId></dependency>");
        pom.append("</dependencies></project>");
        var material=new SourceMaterial(Map.of("pom.xml",bytes(pom.toString())),tiny);
        var bounded=new GraphFacts(tiny,new Rfc8785CanonicalJson(JsonMapper.builder().build()));
        new FileTreeExtractor().extract(material,tiny,bounded);new MavenGraphExtractor().extract(material,tiny,bounded);
        assertTrue(bounded.content().nodes().size()<=tiny.nodes());assertTrue(bounded.content().edges().size()<=tiny.edges());
        assertTrue(bounded.content().gaps().stream().anyMatch(g->g.category().equals("GRAPH_BUDGET_EXCEEDED")));
        assertEquals("PARTIAL",bounded.content().status());
    }
}
