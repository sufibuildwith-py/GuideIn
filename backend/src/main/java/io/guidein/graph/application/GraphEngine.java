package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.Content;
import io.guidein.platform.api.CanonicalJson;
import io.guidein.platform.api.Digests;
import java.util.*;

public final class GraphEngine {
    public static final Set<String> EXCLUSIONS = Set.of(".git", "target", "build", "node_modules", "dist", ".gradle", ".cache");
    private final CanonicalJson canonical; private final GraphLimits limits;
    private final String builderVersion;
    private GraphTelemetry telemetry=GraphTelemetry.disabled();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GraphEngine.class);
    public GraphEngine telemetry(GraphTelemetry telemetry){this.telemetry=telemetry;return this;}
    private final List<GraphExtractor> extractors = List.of(new FileTreeExtractor(), new MavenGraphExtractor(), new GradleGraphExtractor(),
            new NpmGraphExtractor(), new JavaGraphExtractor(), new OpenApiGraphExtractor(), new ComposeGraphExtractor(),
            new DeploymentGraphExtractor(), new GuideInConfigExtractor());
    public record Product(Content content, String digest, String guideinConfigDigest) {}
    public GraphEngine(CanonicalJson canonical, GraphLimits limits) { this(canonical,limits,GraphFacts.BUILDER_VERSION); }
    public GraphEngine(CanonicalJson canonical, GraphLimits limits, String builderVersion) {
        this.canonical = canonical; this.limits = limits;
        if(builderVersion==null || !builderVersion.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid builder version");
        this.builderVersion=builderVersion;
    }
    public String builderVersion() { return builderVersion; }
    public GraphLimits limits() { return limits; }
    public List<String> versions() { return extractors.stream().map(GraphExtractor::version).toList(); }
    public String configurationDigest() {
        return hash(canonical.canonicalize(Map.of("limits", limits, "exclusions", new TreeSet<>(EXCLUSIONS), "extractors", versions())));
    }
    public Product build(Map<String, byte[]> files, List<io.guidein.github.api.RepositoryMaterialSource.Gap> acquisitionGaps, Runnable checkpoint) {
        var material = new SourceMaterial(files, limits); var facts = new GraphFacts(limits, canonical, checkpoint);
        for (var gap : acquisitionGaps) {
            String path;
            try { path = gap.path().isEmpty() ? "" : SourcePaths.normalize(gap.path()); }
            catch (IllegalArgumentException ex) { path = ""; }
            facts.gap(gap.category(), path, "acquisition");
        }
        for (GraphExtractor extractor : extractors) {
            checkpoint.run();long started=System.nanoTime();int priorGaps=facts.gapCount();boolean failed=true;
            try {extractor.extract(material, limits, facts);failed=facts.gapCount()>priorGaps;}
            finally {telemetry.extracted(extractor.version(),System.nanoTime()-started,failed);}
            LOG.info("graph_extractor_completed extractor={}",extractor.version());
        }
        for (String path : material.paths()) if (path.matches(".*\\.(py|go|rs|rb|ts|tsx|js|jsx|cs|cpp|c|kt|scala)$")) facts.gap("UNSUPPORTED_SOURCE_LANGUAGE", path, "");
        var configs = new TreeMap<String, String>();
        for (String path : List.of("guidein.yaml", "guidein.yml", ".guidein/guidein.yaml")) if (material.contains(path)) configs.put(path, material.digest(path));
        checkpoint.run();var collected=facts.content();
        LOG.info("graph_canonicalization_started");
        var content=new Content(builderVersion,collected.confidenceVersion(),collected.nodes(),collected.edges(),collected.gaps());
        return new Product(content, hash(canonical.canonicalize(content)), hash(canonical.canonicalize(configs)));
    }
    public String hash(byte[] bytes) { return HexFormat.of().formatHex(Digests.sha256(bytes)); }
}
