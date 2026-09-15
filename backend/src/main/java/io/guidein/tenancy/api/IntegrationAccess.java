package io.guidein.tenancy.api;

import io.guidein.identity.api.AuthenticatedSubject;
import java.util.UUID;

public interface IntegrationAccess {
    void requireWrite(AuthenticatedSubject subject, UUID tenantId);
    /** Internal machine path: caller must already possess verified provider installation authority. */
    UUID reconcileRepository(UUID tenantId, long externalId, String owner, String name);
}
