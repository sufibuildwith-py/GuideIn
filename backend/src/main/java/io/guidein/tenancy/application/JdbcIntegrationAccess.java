package io.guidein.tenancy.application;

import io.guidein.authorization.api.*;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.platform.api.*;
import io.guidein.tenancy.api.IntegrationAccess;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class JdbcIntegrationAccess implements IntegrationAccess {
    private final JdbcClient jdbc;
    private final TenantContext context;
    private final AuthorizationService authorization;
    JdbcIntegrationAccess(JdbcClient jdbc, TenantContext context, AuthorizationService authorization) {
        this.jdbc=jdbc; this.context=context; this.authorization=authorization;
    }
    @Transactional public void requireWrite(AuthenticatedSubject subject, UUID tenantId) {
        context.setAuthenticatedUser(subject.userId());
        var membership=jdbc.sql("SELECT id,role,scope_mode FROM memberships WHERE tenant_id=:tenant AND user_id=:user")
                .param("tenant",tenantId).param("user",subject.userId())
                .query((rs,n)->new Object[]{rs.getObject(1,UUID.class),AuthorizationRole.valueOf(rs.getString(2)),rs.getString(3)})
                .optional().orElseThrow(()->new GuideInException(ErrorCode.RESOURCE_NOT_FOUND));
        context.setTenant(tenantId);
        authorization.require(subject,new AccessContext((UUID)membership[0],tenantId,subject.userId(),(AuthorizationRole)membership[1]),
                Capability.INTEGRATION_WRITE,new ResourceRef(ResourceRef.ResourceType.TENANT,tenantId,tenantId),
                "ALL_REPOSITORIES".equals(membership[2]));
    }
    @Transactional public UUID reconcileRepository(UUID tenantId,long externalId,String owner,String name) {
        context.setTenant(tenantId);
        if(externalId<1 || owner==null || name==null || !owner.matches("[A-Za-z0-9_.-]{1,100}") || !name.matches("[A-Za-z0-9_.-]{1,100}"))
            throw new GuideInException(ErrorCode.VALIDATION_FAILED);
        return jdbc.sql("""
                INSERT INTO repositories(id,tenant_id,provider,external_id,owner,name,status)
                VALUES (:id,:tenant,'GITHUB',:external,:owner,:name,'ACTIVE')
                ON CONFLICT (tenant_id,provider,external_id) DO UPDATE SET owner=EXCLUDED.owner,name=EXCLUDED.name
                RETURNING id
                """).param("id",UUID.randomUUID()).param("tenant",tenantId).param("external",Long.toString(externalId))
                .param("owner",owner).param("name",name).query(UUID.class).single();
    }
}
