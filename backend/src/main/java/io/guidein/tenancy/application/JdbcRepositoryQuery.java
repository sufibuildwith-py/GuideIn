package io.guidein.tenancy.application;

import io.guidein.authorization.api.AuthorizationService;
import io.guidein.authorization.api.AccessContext;
import io.guidein.authorization.api.AuthorizationRole;
import io.guidein.authorization.api.Capability;
import io.guidein.authorization.api.ResourceRef;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.platform.api.ErrorCode;
import io.guidein.platform.api.GuideInException;
import io.guidein.tenancy.api.Membership;
import io.guidein.tenancy.api.RepositoryQuery;
import io.guidein.tenancy.api.RepositoryView;
import io.guidein.tenancy.api.ScopeMode;
import io.guidein.tenancy.infrastructure.TenantDatabaseContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class JdbcRepositoryQuery implements RepositoryQuery {
    private final JdbcClient jdbc;
    private final TenantDatabaseContext context;
    private final AuthorizationService authorization;

    JdbcRepositoryQuery(JdbcClient jdbc, TenantDatabaseContext context, AuthorizationService authorization) {
        this.jdbc = jdbc;
        this.context = context;
        this.authorization = authorization;
    }

    @Override
    @Transactional(readOnly = true)
    public RepositoryView get(AuthenticatedSubject subject, UUID tenantId, UUID repositoryId) {
        context.setAuthenticatedUser(subject.userId());
        Membership membership = jdbc.sql("""
                SELECT id, tenant_id, user_id, role, scope_mode
                  FROM memberships
                 WHERE user_id = :userId AND tenant_id = :tenantId
                """)
                .param("userId", subject.userId()).param("tenantId", tenantId)
                .query((rs, row) -> new Membership(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class),
                        AuthorizationRole.valueOf(rs.getString("role")), ScopeMode.valueOf(rs.getString("scope_mode"))))
                .optional().orElseThrow(() -> new GuideInException(ErrorCode.RESOURCE_NOT_FOUND));

        context.setTenant(tenantId);
        RepositoryView repository = jdbc.sql("""
                SELECT id, tenant_id, provider, owner, name, status
                  FROM repositories WHERE id = :id
                """).param("id", repositoryId)
                .query((rs, row) -> new RepositoryView(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("provider"), rs.getString("owner"),
                        rs.getString("name"), rs.getString("status")))
                .optional().orElseThrow(() -> new GuideInException(ErrorCode.RESOURCE_NOT_FOUND));

        boolean inScope = membership.scopeMode() == ScopeMode.ALL_REPOSITORIES || jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM membership_repository_scopes
                 WHERE membership_id = :membershipId AND repository_id = :repositoryId)
                """).param("membershipId", membership.id()).param("repositoryId", repositoryId)
                .query(Boolean.class).single();
        authorization.require(subject, new AccessContext(membership.id(), membership.tenantId(),
                        membership.userId(), membership.role()), Capability.REPOSITORY_READ,
                new ResourceRef(ResourceRef.ResourceType.REPOSITORY, tenantId, repositoryId), inScope);
        return repository;
    }
}
