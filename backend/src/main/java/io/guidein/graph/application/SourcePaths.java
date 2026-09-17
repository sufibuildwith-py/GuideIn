package io.guidein.graph.application;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class SourcePaths {
    private static final Set<String> EXCLUDED = Set.of(".git", "target", "build", "node_modules", "dist", ".gradle", ".cache");
    private SourcePaths() {}
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 4096) throw new IllegalArgumentException("PATH_REJECTED");
        String path = raw.replace('\\', '/');
        if (path.startsWith("/") || path.contains(":") || path.codePoints().anyMatch(c -> c < 32 || c == 127)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(path) || path.getBytes(StandardCharsets.UTF_8).length > 1024) throw new IllegalArgumentException("PATH_REJECTED");
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals("..") || part.equals(".")) throw new IllegalArgumentException("PATH_REJECTED");
        return path;
    }
    public static boolean excluded(String path) {
        for (String part : path.split("/")) if (EXCLUDED.contains(part)) return true;
        return false;
    }
    public static String directory(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "." : path.substring(0, slash); }
}
