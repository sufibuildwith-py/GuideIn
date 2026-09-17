package io.guidein.graph.api;

import java.util.List;
import java.util.Map;

/** Semantic graph content only: no database IDs, timestamps or source bodies. */
public final class GraphModel {
    private GraphModel() {}
    public enum NodeType { REPOSITORY, MODULE, PACKAGE, FILE, SYMBOL, SERVICE, API, ENDPOINT, EXTERNAL_PROVIDER, DEPLOYMENT, CONFIGURATION }
    public enum EdgeType { CONTAINS, IMPORTS, DECLARES, DEPENDS_ON, EXPOSES, DEPLOYED_AS }
    public enum Trust { EXPLICIT, RESOLVED }
    public enum Source {
        GUIDEIN(1, "1.00", Trust.EXPLICIT), MAVEN(2, "1.00", Trust.EXPLICIT),
        GRADLE(2, "0.90", Trust.EXPLICIT), NPM(2, "1.00", Trust.EXPLICIT),
        JAVA(3, "0.95", Trust.RESOLVED), OPENAPI(4, "1.00", Trust.EXPLICIT),
        COMPOSE(5, "1.00", Trust.EXPLICIT), DEPLOYMENT(5, "1.00", Trust.EXPLICIT),
        FILE_TREE(6, "1.00", Trust.EXPLICIT);
        public static final String REGISTRY_VERSION = "source-trust-v1";
        public final int precedence;
        public final String confidence;
        public final Trust trust;
        Source(int precedence, String confidence, Trust trust) {
            this.precedence = precedence; this.confidence = confidence; this.trust = trust;
        }
    }
    public record Node(String key, NodeType type, String displayName, String criticality,
                       String sourceIdentity, Map<String, String> metadata) {
        public Node { metadata = Map.copyOf(metadata); }
    }
    public record Evidence(Source source, String path, String locator, String digest,
                           String extractorVersion, String observation, Trust trust, String confidence,
                           Map<String, String> metadata) {
        public Evidence {
            metadata = Map.copyOf(metadata);
            if (trust != source.trust || !confidence.equals(source.confidence))
                throw new IllegalArgumentException("Evidence must use the versioned source registry");
        }
    }
    public record Edge(String from, EdgeType type, String to, List<Evidence> evidence) {
        public Edge { evidence = List.copyOf(evidence); if (evidence.isEmpty()) throw new IllegalArgumentException("Missing edge evidence"); }
        public String key() { return from.length() + ":" + from + ":" + type + ":" + to; }
    }
    public record Gap(String category, String path, String locator) {}
    public record Content(String builderVersion, String confidenceVersion, List<Node> nodes,
                          List<Edge> edges, List<Gap> gaps) {
        public Content { nodes = List.copyOf(nodes); edges = List.copyOf(edges); gaps = List.copyOf(gaps); }
        public String status() { return gaps.isEmpty() ? "READY" : "PARTIAL"; }
    }
}
