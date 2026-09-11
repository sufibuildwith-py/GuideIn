package io.guidein.tenancy.api;

import io.guidein.identity.api.AuthenticatedSubject;
import java.util.UUID;

public record CreateRepositoryCommand(AuthenticatedSubject subject, UUID tenantId, UUID repositoryId,
                                      String externalId, String owner, String name, UUID correlationId) {}

