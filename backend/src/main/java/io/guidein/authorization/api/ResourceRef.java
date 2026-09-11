package io.guidein.authorization.api;

import java.util.UUID;

public record ResourceRef(ResourceType type, UUID tenantId, UUID resourceId) {
    public enum ResourceType { TENANT, MEMBERSHIP, REPOSITORY, AUDIT, PLATFORM }
}

