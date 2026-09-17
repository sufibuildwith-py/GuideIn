package io.guidein.graph;

import io.guidein.graph.api.GraphModel.*;
import io.guidein.graph.application.*;
import io.guidein.platform.infrastructure.Rfc8785CanonicalJson;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class GraphOracleEvaluationTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private String key(String from,String type,String to){return from.length()+":"+from+":"+type+":"+to;}
    private String key(JsonNode edge){return key(edge.path("from").asText(),edge.path("type").asText(),edge.path("to").asText());}
    private Set<String> strings(JsonNode array){var result=new TreeSet<String>();array.forEach(n->result.add(n.asText()));return result;}
    private Set<String> difference(Set<String> a,Set<String> b){var result=new TreeSet<>(a);result.removeAll(b);return result;}
    @Test void allTwentyIndependentOraclesMeasureEveryTrustedEdgeWithNoRepositoryExecution() throws Exception {
        Path root=Path.of("../evaluation/fixtures/graph"),proof=Path.of("target/proof");Files.createDirectories(proof);
        var engine=new GraphEngine(new Rfc8785CanonicalJson(mapper),GraphLimits.defaults());
        var results=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
        var perSource=new TreeMap<String,long[]>();for(Source source:Source.values())perSource.put(source.name(),new long[5]);
        long requiredTotal=0,foundTotal=0,falseTotal=0,emittedTotal=0,gapTotal=0;
        try(var recording=new Recording()) {
            recording.enable("jdk.ProcessStart");recording.start();
            List<Path> fixtures;try(var paths=Files.list(root)){fixtures=paths.filter(Files::isDirectory).sorted().toList();}assertEquals(20,fixtures.size());
            for(Path fixture:fixtures) {
                String name=fixture.getFileName().toString();
                var expectedNodes=mapper.readTree(Files.readString(fixture.resolve("oracle/nodes.json")));
                var expectedEdges=mapper.readTree(Files.readString(fixture.resolve("oracle/edges.json")));
                var expectedGaps=mapper.readTree(Files.readString(fixture.resolve("oracle/gaps.json")));
                assertEquals("REVIEWED",expectedEdges.path("status").asText(),name);
                var required=new TreeSet<String>();var allowed=new TreeSet<String>();var forbidden=new TreeSet<String>();
                expectedEdges.path("required").forEach(e->{assertEquals("REQUIRED_TRUSTED",e.path("classification").asText());required.add(key(e));});allowed.addAll(required);
                expectedEdges.path("optional").forEach(e->allowed.add(key(e)));expectedEdges.path("must_not_exist").forEach(e->forbidden.add(key(e)));
                var input=new TreeMap<String,byte[]>();Path source=fixture.resolve("source");
                if(name.startsWith("17-")) {
                    // Fixture specification, not extractor output: exactly 2,000 inert text files.
                    for(int i=0;i<2000;i++)input.put("src/File%04d.txt".formatted(i),"data".getBytes(StandardCharsets.UTF_8));
                } else if(name.startsWith("18-")) {
                    input.put("safe.txt","safe".getBytes(StandardCharsets.UTF_8));
                    for(var hostile:mapper.readTree(Files.readString(source.resolve("hostile-paths.json"))))input.put(hostile.asText(),"untrusted".getBytes(StandardCharsets.UTF_8));
                } else try(var paths=Files.walk(source)) {
                    for(Path file:paths.filter(Files::isRegularFile).toList()) {
                        String relative=source.relativize(file).toString().replace('\\','/');
                        if(!SourcePaths.excluded(relative))input.put(relative,Files.readAllBytes(file));
                    }
                }
                long started=System.nanoTime();var product=engine.build(input,List.of(),()->{});var content=product.content();
                var actual=new TreeSet<String>();content.edges().forEach(e->actual.add(e.key()));
                var actualNodes=new TreeSet<String>();content.nodes().forEach(n->actualNodes.add(n.key()));
                var gapCategories=new TreeSet<String>();content.gaps().forEach(g->gapCategories.add(g.category()));
                var missing=difference(required,actual);var falseEdges=difference(actual,allowed);var banned=new TreeSet<>(actual);banned.retainAll(forbidden);
                var missingNodes=difference(strings(expectedNodes.path("required_node_keys")),actualNodes);var unexpectedNodes=difference(actualNodes,strings(expectedNodes.path("required_node_keys")));
                var missingGaps=difference(strings(expectedGaps.path("required_categories")),gapCategories);var extraGaps=difference(gapCategories,strings(expectedGaps.path("allowed_categories")));
                if(!missing.isEmpty()||!falseEdges.isEmpty()||!banned.isEmpty()||!missingNodes.isEmpty()||!unexpectedNodes.isEmpty()||!missingGaps.isEmpty()||!extraGaps.isEmpty())failures.add(name+": missing="+missing+", false="+falseEdges+", forbidden="+banned+", missing nodes="+missingNodes+", extra nodes="+unexpectedNodes+", missing gaps="+missingGaps+", extra gaps="+extraGaps);
                var item=new TreeMap<String,Object>();item.put("fixture",name);item.put("required",required.size());item.put("correct",required.size()-missing.size());item.put("missing",missing);item.put("false_trusted",falseEdges);item.put("optional_emitted",actual.stream().filter(e->allowed.contains(e)&&!required.contains(e)).count());item.put("heuristic_edges",0);item.put("trusted_edges",actual.size());item.put("gaps",content.gaps().size());item.put("unexpected_gaps",extraGaps);item.put("elapsed_ms",(System.nanoTime()-started)/1_000_000.0);results.add(item);
                for(var edge:expectedEdges.path("required")){long[] totals=perSource.get(edge.path("source").asText());totals[0]++;if(actual.contains(key(edge)))totals[1]++;else totals[2]++;}
                for(Edge edge:content.edges())if(falseEdges.contains(edge.key()))edge.evidence().stream().map(Evidence::source).distinct().forEach(s->perSource.get(s.name())[3]++);
                for(Gap gap:content.gaps()){Source owner=gapOwner(gap.category());if(owner!=null)perSource.get(owner.name())[4]++;}
                requiredTotal+=required.size();foundTotal+=required.size()-missing.size();falseTotal+=falseEdges.size();emittedTotal+=actual.size();gapTotal+=content.gaps().size();
            }
            recording.stop();Path jfr=proof.resolve("graph-oracle-execution.jfr");recording.dump(jfr);
            long processes=RecordingFile.readAllEvents(jfr).stream().filter(e->e.getEventType().getName().equals("jdk.ProcessStart")).count();
            var totals=new TreeMap<String,Object>();totals.put("fixtures",results);totals.put("per_extractor",perSource);totals.put("required",requiredTotal);totals.put("correct",foundTotal);totals.put("false_trusted",falseTotal);totals.put("trusted_edges",emittedTotal);totals.put("heuristic_edges",0);totals.put("gaps",gapTotal);totals.put("recall",(double)foundTotal/requiredTotal);totals.put("false_trusted_edge_rate",(double)falseTotal/emittedTotal);totals.put("repository_code_executions",processes);totals.put("model_provider","not configured; pure graph engine has no model dependency");totals.put("failures",failures);
            Files.writeString(proof.resolve("graph-oracle-evaluation.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(totals));
            assertEquals(0,processes,"No process creation during complete fixture extraction");
            assertTrue(failures.isEmpty(),String.join("\n",failures));assertTrue((double)foundTotal/requiredTotal>=0.98);assertTrue((double)falseTotal/emittedTotal<=0.01);
        }
    }
    private Source gapOwner(String category){
        if(category.startsWith("JAVA_")||category.equals("UNRESOLVED_SYMBOL")||category.equals("INVALID_UNICODE"))return Source.JAVA;
        if(category.startsWith("DOCKERFILE_")||category.startsWith("DEPLOYMENT_"))return Source.DEPLOYMENT;
        if(category.startsWith("PATH_")||category.startsWith("SOURCE_")||category.startsWith("FILE_"))return Source.FILE_TREE;
        for(Source source:Source.values())if(category.startsWith(source.name()))return source;
        return null;
    }
}
