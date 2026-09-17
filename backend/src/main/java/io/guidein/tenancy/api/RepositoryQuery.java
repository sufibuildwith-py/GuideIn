package io.guidein.tenancy.api;

import io.guidein.identity.api.AuthenticatedSubject;
import java.util.UUID;

public interface RepositoryQuery {
    RepositoryView get(AuthenticatedSubject subject, UUID tenantId, UUID repositoryId);
    RepositoryView requireManage(AuthenticatedSubject subject, UUID tenantId, UUID repositoryId);
    void requireMembership(AuthenticatedSubject subject, UUID tenantId);
}

