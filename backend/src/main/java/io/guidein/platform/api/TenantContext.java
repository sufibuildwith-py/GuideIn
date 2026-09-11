package io.guidein.platform.api;

import java.util.UUID;

public interface TenantContext {
    void setAuthenticatedUser(UUID userId);
    void setTenant(UUID tenantId);
}

