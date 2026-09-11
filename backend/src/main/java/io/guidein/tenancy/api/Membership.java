package io.guidein.tenancy.api;

import io.guidein.authorization.api.AuthorizationRole;
import java.util.UUID;

public record Membership(UUID id, UUID tenantId, UUID userId, AuthorizationRole role, ScopeMode scopeMode) {}
