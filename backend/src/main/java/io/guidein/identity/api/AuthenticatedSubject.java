package io.guidein.identity.api;

import java.util.UUID;

public record AuthenticatedSubject(UUID userId, String issuer, String externalSubject) {
    public AuthenticatedSubject {
        if (userId == null || issuer == null || issuer.isBlank() || externalSubject == null || externalSubject.isBlank()) {
            throw new IllegalArgumentException("A subject requires internal and external identity");
        }
    }
}

