package io.guidein.authorization.api;

import java.util.UUID;

public record AccessContext(UUID membershipId, UUID tenantId, UUID userId, AuthorizationRole role) {}

