package io.guidein.identity.api;

public interface IdentityResolver {
    AuthenticatedSubject resolve(String issuer, String subject, String email, String displayName);
}

