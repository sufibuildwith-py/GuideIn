package io.guidein.tenancy.api;

import java.util.UUID;

public record RepositoryView(UUID id, UUID tenantId, String provider, String owner, String name, String status) {}

