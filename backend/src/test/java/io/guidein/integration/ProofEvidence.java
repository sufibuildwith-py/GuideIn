package io.guidein.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

final class ProofEvidence {
    static void write(String name, Object value) {
        try {
            Path directory = Path.of("target", "proof");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name + ".json"), JsonMapper.builder().build()
                    .writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (Exception exception) { throw new IllegalStateException("Cannot retain proof evidence", exception); }
    }
}
