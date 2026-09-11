package io.guidein.platform.api;

public interface CanonicalJson {
    byte[] canonicalize(Object value);
    byte[] canonicalizeJson(String json);
}

