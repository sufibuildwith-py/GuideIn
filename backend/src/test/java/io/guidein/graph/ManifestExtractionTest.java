package io.guidein.graph;

import io.guidein.graph.api.GraphModel.*;
import io.guidein.graph.application.*;
import io.guidein.platform.infrastructure.Rfc8785CanonicalJson;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ManifestExtractionTest {
    private final GraphLimits limits = GraphLimits.defaults();
    private GraphFacts extract(Map<String, byte[]> input, GraphExtractor extractor) {
        var material = new SourceMaterial(input, limits);
        var facts = new GraphFacts(limits, new Rfc8785CanonicalJson(JsonMapper.builder().build()));
        new FileTreeExtractor().extract(material, limits, facts); extractor.extract(material, limits, facts); return facts;
    }
    private GraphFacts fixture(String name, GraphExtractor extractor) throws Exception {
        Path root = Path.of("../evaluation/fixtures/graph", name, "source");
        var files = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) files.put(root.relativize(path).toString(), Files.readAllBytes(path));
        }
        return extract(files, extractor);
    }
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private boolean edge(GraphFacts facts, String from, String to) {
        return facts.content().edges().stream().anyMatch(e -> e.from().equals(from) && e.to().equals(to) && e.type() == EdgeType.DEPENDS_ON);
    }
    @Test void mavenExplicitDependencyAndReactorAreDistinct() throws Exception {
        assertTrue(edge(fixture("01-simple-maven", new MavenGraphExtractor()), "module:.", "external:maven:org.libs:core"));
        var reactor = fixture("02-multi-module-maven", new MavenGraphExtractor());
        assertTrue(edge(reactor, "module:api", "module:core"));
        assertFalse(reactor.content().nodes().stream().anyMatch(n -> n.key().equals("external:maven:org.demo:core")));
    }
    @Test void mavenLocalParentIsRecorded() throws Exception {
        assertTrue(edge(fixture("03-maven-parent", new MavenGraphExtractor()), "module:child", "module:."));
    }
    @Test void mavenDoesNotReadExternalEntitiesAndContinuesOtherFiles() {
        var facts = extract(Map.of("pom.xml", bytes("<!DOCTYPE project [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><project><artifactId>&xxe;</artifactId></project>"),
                "safe/pom.xml", bytes("<project><groupId>g</groupId><artifactId>safe</artifactId></project>")), new MavenGraphExtractor());
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("MAVEN_INVALID")));
        assertTrue(facts.hasNode("module:safe"));
        assertFalse(new String(facts.canonicalBytes(), StandardCharsets.UTF_8).contains("root:x:"));
    }
    @Test void unknownMavenEffectiveInputsAreNotClaimedComplete() {
        var facts = extract(Map.of("pom.xml", bytes("<project><groupId>g</groupId><artifactId>a</artifactId><profiles/><dependencies><dependency><groupId>${unknown}</groupId><artifactId>b</artifactId></dependency></dependencies></project>")), new MavenGraphExtractor());
        assertEquals("PARTIAL", facts.content().status());
        assertFalse(facts.content().edges().stream().anyMatch(e -> e.type() == EdgeType.DEPENDS_ON));
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("MAVEN_PROFILE_DYNAMIC")));
    }
    @Test void npmWorkspacesResolveExactIdentity() throws Exception {
        assertTrue(edge(fixture("06-npm-workspaces", new NpmGraphExtractor()), "module:packages/api", "module:packages/core"));
    }
    @Test void npmMatchingNameWithoutWorkspaceDoesNotBecomeInternalDependency() {
        var facts = extract(Map.of("a/package.json", bytes("{\"name\":\"a\",\"dependencies\":{\"b\":\"1\"}}"), "b/package.json", bytes("{\"name\":\"b\",\"version\":\"1\"}")), new NpmGraphExtractor());
        assertFalse(edge(facts, "module:a", "module:b")); assertTrue(edge(facts, "module:a", "external:npm:b"));
    }
    @Test void composeOnlyUsesDeclaredDependenciesAndKeepsCycles() throws Exception {
        var compose = fixture("12-docker-compose", new ComposeGraphExtractor());
        assertTrue(edge(compose, "service:compose:api", "service:compose:db"));
        assertFalse(edge(compose, "service:compose:api", "service:compose:unrelated"));
        var cycle = fixture("13-compose-cycle", new ComposeGraphExtractor());
        assertTrue(edge(cycle, "service:compose:a", "service:compose:b"));
        assertTrue(edge(cycle, "service:compose:b", "service:compose:c"));
        assertTrue(edge(cycle, "service:compose:c", "service:compose:a"));
    }
    @Test void yamlAliasesTagsDuplicateKeysAndDepthFailClosed() {
        for (String yaml : List.of("a: &a [1,2]\nb: *a", "a: !!java.net.URL [https://example.com]", "a: 1\na: 2", "a: " + "[".repeat(100) + "0" + "]".repeat(100))) {
            var material = new SourceMaterial(Map.of("test.yaml", bytes(yaml)), limits);
            assertThrows(RuntimeException.class, () -> new BoundedDocuments(limits).yaml(material, "test.yaml", limits.manifestBytes()));
        }
    }
    @Test void jsonDuplicateKeysAndDepthFailClosed() {
        for (String json : List.of("{\"a\":1,\"a\":2}", "[".repeat(100) + "0" + "]".repeat(100))) {
            var material = new SourceMaterial(Map.of("test.json", bytes(json)), limits);
            assertThrows(RuntimeException.class, () -> new BoundedDocuments(limits).json(material, "test.json", limits.manifestBytes()));
        }
    }
    @Test void gradleStaticProjectsResolveWithoutExecutingDynamicConfiguration() throws Exception {
        assertTrue(edge(fixture("04-gradle-multiproject-static", new GradleGraphExtractor()), "module:api", "module:shared"));
        var dynamic = fixture("05-gradle-dynamic-unresolved", new GradleGraphExtractor());
        assertFalse(dynamic.content().edges().stream().anyMatch(e -> e.type() == EdgeType.DEPENDS_ON));
        assertTrue(dynamic.content().gaps().stream().anyMatch(g -> g.category().equals("GRADLE_DYNAMIC_CONFIGURATION")));
    }
    @Test void openApiHasExactOperationsAndBoundedLocalReferences() throws Exception {
        var api = fixture("09-openapi-service", new OpenApiGraphExtractor());
        assertTrue(api.content().edges().stream().anyMatch(e -> e.from().equals("api:openapi.json") && e.to().equals("endpoint:openapi.json:GET:/orders/{id}")));
        assertEquals("READY", fixture("10-openapi-local-ref", new OpenApiGraphExtractor()).content().status());
        var malicious = fixture("11-openapi-malicious-remote-ref", new OpenApiGraphExtractor());
        assertTrue(malicious.content().gaps().stream().anyMatch(g -> g.category().equals("OPENAPI_REF_BLOCKED")));
    }
    @Test void cyclicOpenApiReferencesTerminateAndEscapingReferencesAreBlocked() {
        var cyclic = extract(Map.of("openapi.json", bytes("{\"openapi\":\"3.0.3\",\"paths\":{},\"components\":{\"schemas\":{\"A\":{\"$ref\":\"#/components/schemas/A\"}}}}")), new OpenApiGraphExtractor());
        assertEquals("READY", cyclic.content().status());
        for (String ref : List.of("../../secret.json", "/secret.json", "file:///secret.json", "https://example.com/x", "http://169.254.169.254/latest/meta-data")) {
            var malicious = extract(Map.of("openapi.json", bytes("{\"openapi\":\"3.0.3\",\"paths\":{},\"x\":{\"$ref\":\"" + ref + "\"}}")), new OpenApiGraphExtractor());
            assertTrue(malicious.content().gaps().stream().anyMatch(g -> g.category().equals("OPENAPI_REF_BLOCKED")), ref);
        }
    }
    @Test void openApiDotRelativeRefResolvesAndLegalRecursiveSchemaTerminates() {
        var facts=extract(Map.of("api/openapi.yaml",bytes("openapi: 3.0.3\npaths: {}\ncomponents:\n  schemas:\n    Node:\n      $ref: './schemas/common.yaml#/Node'\n"),
                "api/schemas/common.yaml",bytes("Node:\n  type: object\n  properties:\n    children:\n      type: array\n      items:\n        $ref: '#/Node'\n")),new OpenApiGraphExtractor());
        assertEquals("READY",facts.content().status());assertTrue(facts.content().gaps().isEmpty());
    }
    @Test void kubernetesSelectorHasEvidenceFromBothResources() throws Exception {
        var facts = fixture("14-kubernetes-basic", new DeploymentGraphExtractor());
        var relation = facts.content().edges().stream().filter(e -> e.from().equals("deployment:default:Deployment:api") && e.to().equals("service:kubernetes:default:api")).findFirst().orElseThrow();
        assertEquals(EdgeType.DEPLOYED_AS, relation.type()); assertEquals(2, relation.evidence().size());
    }
    @Test void statefulSetAndDockerfileFactsStayStaticAndExplainable() {
        var facts=extract(Map.of("Dockerfile",bytes("FROM eclipse-temurin:21\nEXPOSE 8080\nCOPY app.jar /app.jar\nRUN curl example.invalid\n"),
                "stateful.yaml",bytes("apiVersion: apps/v1\nkind: StatefulSet\nmetadata:\n  name: db\nspec:\n  template:\n    metadata:\n      labels:\n        app: db\n---\napiVersion: v1\nkind: Service\nmetadata:\n  name: db\nspec:\n  selector:\n    app: db\n")),new DeploymentGraphExtractor());
        assertTrue(facts.hasNode("deployment:default:StatefulSet:db"));assertTrue(facts.hasNode("deployment:dockerfile:Dockerfile"));
        assertTrue(facts.content().edges().stream().anyMatch(e->e.from().equals("deployment:default:StatefulSet:db")&&e.to().equals("service:kubernetes:default:db")&&e.evidence().size()==2));
        assertTrue(facts.content().gaps().stream().anyMatch(g->g.category().equals("DOCKERFILE_DYNAMIC_TOPOLOGY")));
        assertFalse(new String(facts.canonicalBytes(),StandardCharsets.UTF_8).contains("curl example.invalid"));
    }
    @Test void guideInConfigCriticalityAndPathMembershipAreExplicit() throws Exception {
        var facts = fixture("20-guidein-explicit-config", new GuideInConfigExtractor());
        assertEquals("HIGH", facts.content().nodes().stream().filter(n -> n.key().equals("repo:root")).findFirst().orElseThrow().criticality());
        assertTrue(facts.content().nodes().stream().anyMatch(n -> n.key().startsWith("module:guidein:") && n.criticality().equals("CRITICAL")));
        assertEquals("READY", facts.content().status());
    }
    @Test void guideInUnknownSafetyKeyRejectsEntireConfiguration() {
        var facts = extract(Map.of("guidein.yaml", bytes("guidein:\n  version: 1\n  repository:\n    criticality: HIGH\n    run: curl evil\n")), new GuideInConfigExtractor());
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("GUIDEIN_CONFIG_INVALID")));
        assertEquals("UNKNOWN", facts.content().nodes().stream().filter(n -> n.key().equals("repo:root")).findFirst().orElseThrow().criticality());
    }
    @Test void gradleConditionalDependencyCannotBecomeUnconditionalTrustedEdge() {
        var facts = extract(Map.of("build.gradle.kts", bytes("if (false) {\ndependencies { implementation(\"g:artifact:1\") }\n}")), new GradleGraphExtractor());
        assertFalse(facts.content().edges().stream().anyMatch(e -> e.type() == EdgeType.DEPENDS_ON));
        assertTrue(facts.content().gaps().stream().anyMatch(g -> g.category().equals("GRADLE_DYNAMIC_CONFIGURATION")));
    }
}
