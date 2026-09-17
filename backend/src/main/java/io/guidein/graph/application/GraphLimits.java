package io.guidein.graph.application;

public record GraphLimits(int files, long totalBytes, int fileBytes, int manifestBytes,
                          int openApiBytes, int javaBytes, int nesting, int nodes, int edges, int gaps) {
    public GraphLimits {
        if (files < 1 || totalBytes < 1 || fileBytes < 1 || manifestBytes < 1 || openApiBytes < 1
                || javaBytes < 1 || nesting < 1 || nodes < 2 || edges < 1 || gaps < 1)
            throw new IllegalArgumentException("Graph limits must be positive");
    }
    public static GraphLimits defaults() {
        return new GraphLimits(20_000, 64L * 1024 * 1024, 2 * 1024 * 1024, 512 * 1024,
                2 * 1024 * 1024, 256 * 1024, 64, 100_000, 500_000, 2_000);
    }
}
