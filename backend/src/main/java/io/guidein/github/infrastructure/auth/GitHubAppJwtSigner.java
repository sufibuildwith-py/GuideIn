package io.guidein.github.infrastructure.auth;

/** A sign-only implementation may delegate to a vault without exporting its key. */
public interface GitHubAppJwtSigner {
    String createJwt();
}
