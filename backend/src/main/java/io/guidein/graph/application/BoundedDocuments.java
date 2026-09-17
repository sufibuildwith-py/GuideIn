package io.guidein.graph.application;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Offline parsers with no repository-provided classes, aliases, resolvers or duplicate keys. */
public final class BoundedDocuments {
    private final JsonMapper json;
    private final GraphLimits limits;
    public BoundedDocuments(GraphLimits limits) {
        this.limits = limits;
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(limits.nesting()).maxStringLength(limits.openApiBytes()).maxNumberLength(100).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        json = JsonMapper.builder(factory).build();
    }
    public JsonNode json(SourceMaterial material, String path, int maxBytes) {
        return json.readTree(material.text(path, maxBytes));
    }
    public List<JsonNode> yaml(SourceMaterial material, String path, int maxBytes) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false); options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(limits.nesting());
        options.setCodePointLimit(maxBytes);
        var parser = new Yaml(new SafeConstructor(options));
        var documents = new ArrayList<JsonNode>();
        for (Object document : parser.loadAll(material.text(path, maxBytes))) {
            if (documents.size() >= 256) throw new IllegalArgumentException("DOCUMENT_COUNT_LIMIT");
            validateJsonShape(document, 0); documents.add(json.valueToTree(document));
        }
        return List.copyOf(documents);
    }
    public JsonNode document(SourceMaterial material, String path, int maxBytes) {
        if (path.endsWith(".json")) return json(material, path, maxBytes);
        var docs = yaml(material, path, maxBytes);
        if (docs.size() != 1) throw new IllegalArgumentException("SINGLE_DOCUMENT_REQUIRED");
        return docs.getFirst();
    }
    private void validateJsonShape(Object value, int depth) {
        if (depth > limits.nesting()) throw new IllegalArgumentException("DEPTH_LIMIT");
        if (value instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                if (!(e.getKey() instanceof String)) throw new IllegalArgumentException("STRING_KEY_REQUIRED");
                validateJsonShape(e.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) for (Object item : list) validateJsonShape(item, depth + 1);
        else if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean))
            throw new IllegalArgumentException("JSON_SCALAR_REQUIRED");
    }
}
