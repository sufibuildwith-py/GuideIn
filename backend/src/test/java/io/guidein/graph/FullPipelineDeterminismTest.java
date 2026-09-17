package io.guidein.graph;

import io.guidein.graph.application.*;
import io.guidein.platform.infrastructure.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class FullPipelineDeterminismTest {
    @Test void extractorInvocationOrderCannotDiscardRelationshipsOrMetadata() {
        var limits=GraphLimits.defaults();var canonical=new Rfc8785CanonicalJson(JsonMapper.builder().build());
        var files=new LinkedHashMap<String,byte[]>();
        Map.of("pom.xml","<project><groupId>g</groupId><artifactId>app</artifactId></project>",
                "src/App.java","package sample; class App {}",
                "compose.yaml","services:\n  app:\n    depends_on: [db]\n  db: {}\n",
                "guidein.yaml","guidein:\n  version: 1\n  repository:\n    criticality: HIGH\n").forEach((p,v)->files.put(p,v.getBytes(StandardCharsets.UTF_8)));
        var material=new SourceMaterial(files,limits);
        var extractors=new ArrayList<GraphExtractor>(List.of(new FileTreeExtractor(),new MavenGraphExtractor(),new JavaGraphExtractor(),new ComposeGraphExtractor(),new GuideInConfigExtractor()));
        String expected=null;
        for(int run=0;run<100;run++) {
            Collections.shuffle(extractors,new Random(run));var facts=new GraphFacts(limits,canonical);
            extractors.forEach(e->e.extract(material,limits,facts));
            assertEquals("READY",facts.content().status(),"order "+extractors.stream().map(GraphExtractor::version).toList());
            if(expected==null)expected=facts.digest();else assertEquals(expected,facts.digest(),"order "+run);
            assertEquals("HIGH",facts.content().nodes().stream().filter(n->n.key().equals("repo:root")).findFirst().orElseThrow().criticality());
            assertEquals("app",facts.content().nodes().stream().filter(n->n.key().equals("module:.")).findFirst().orElseThrow().displayName());
        }
    }
}
