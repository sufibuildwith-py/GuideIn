package io.guidein.platform.infrastructure;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import io.guidein.platform.api.CanonicalJson;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public final class Rfc8785CanonicalJson implements CanonicalJson {
    private final ObjectMapper mapper;

    public Rfc8785CanonicalJson(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    public byte[] canonicalize(Object value) {
        try {
            return new org.erdtman.jcs.JsonCanonicalizer(mapper.writeValueAsBytes(value)).getEncodedUTF8();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Payload is not valid I-JSON", exception);
        }
    }

    @Override
    public byte[] canonicalizeJson(String json) {
        try {
            return new org.erdtman.jcs.JsonCanonicalizer(json.getBytes(StandardCharsets.UTF_8)).getEncodedUTF8();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Payload is not valid I-JSON", exception);
        }
    }
}
