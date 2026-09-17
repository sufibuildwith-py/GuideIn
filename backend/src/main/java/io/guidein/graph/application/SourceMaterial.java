package io.guidein.graph.application;

import io.guidein.graph.api.GraphModel.Gap;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;

/** Bounded virtual source root. Nothing is written to disk or executed. */
public final class SourceMaterial {
    private final Map<String, byte[]> files;
    private final List<Gap> gaps;
    private final java.util.concurrent.ConcurrentMap<String, String> digests = new java.util.concurrent.ConcurrentHashMap<>();
    public SourceMaterial(Map<String, byte[]> input, GraphLimits limits) {
        TreeMap<String, byte[]> accepted = new TreeMap<>();
        ArrayList<Gap> rejected = new ArrayList<>();
        long total = 0;
        var collisions = new HashSet<String>();
        // A sorted discovery order makes limit selection reproducible for the same input set.
        for (var entry : new TreeMap<>(input).entrySet()) {
            String path;
            try { path = SourcePaths.normalize(entry.getKey()); }
            catch (IllegalArgumentException ex) { gap(rejected, limits, "PATH_REJECTED", ""); continue; }
            if (SourcePaths.excluded(path)) continue;
            byte[] value = entry.getValue();
            if (value == null) { gap(rejected, limits, "SOURCE_UNAVAILABLE", path); continue; }
            if (collisions.contains(path)) continue;
            if (accepted.containsKey(path)) {
                gap(rejected, limits, "PATH_COLLISION", path); total -= accepted.remove(path).length;
                collisions.add(path); continue;
            }
            if (value.length > limits.fileBytes()) { gap(rejected, limits, "FILE_BYTE_LIMIT", path); continue; }
            if (accepted.size() >= limits.files() || total + value.length > limits.totalBytes()) {
                gap(rejected, limits, "SOURCE_BUDGET_EXCEEDED", ""); break;
            }
            accepted.put(path, value.clone()); total += value.length;
        }
        files = Collections.unmodifiableMap(accepted); gaps = List.copyOf(rejected);
    }
    private static void gap(List<Gap> gaps, GraphLimits limits, String category, String path) {
        if (gaps.size() < limits.gaps()) gaps.add(new Gap(category, path, ""));
    }
    public List<String> paths() { return List.copyOf(files.keySet()); }
    public boolean contains(String path) { return files.containsKey(path); }
    public byte[] bytes(String path) { return files.get(path).clone(); }
    public String digest(String path) {
        return digests.computeIfAbsent(path, key -> java.util.HexFormat.of().formatHex(io.guidein.platform.api.Digests.sha256(files.get(key))));
    }
    public String text(String path, int limit) {
        byte[] bytes = files.get(path);
        if (bytes.length > limit) throw new IllegalArgumentException("PARSE_BYTE_LIMIT");
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException ex) { throw new IllegalArgumentException("INVALID_UNICODE"); }
    }
    public List<Gap> gaps() { return gaps; }
}
