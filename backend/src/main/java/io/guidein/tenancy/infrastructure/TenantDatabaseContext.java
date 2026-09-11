package io.guidein.tenancy.infrastructure;

import io.guidein.platform.api.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class TenantDatabaseContext implements TenantContext {
    private final JdbcClient jdbc;

    public TenantDatabaseContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void setAuthenticatedUser(UUID userId) {
        jdbc.sql("SELECT set_config('guidein.user_id', :value, true)")
                .param("value", userId.toString()).query(String.class).single();
    }

    @Override
    public void setTenant(UUID tenantId) {
        jdbc.sql("SELECT set_config('guidein.tenant_id', :value, true)")
                .param("value", tenantId.toString()).query(String.class).single();
    }
}
