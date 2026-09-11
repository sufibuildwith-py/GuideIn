package io.guidein.tenancy.application;

import io.guidein.audit.api.AuditCommand;
import io.guidein.audit.api.AuditLedger;
import io.guidein.authorization.api.AccessContext;
import io.guidein.authorization.api.AuthorizationRole;
import io.guidein.authorization.api.AuthorizationService;
import io.guidein.authorization.api.Capability;
import io.guidein.authorization.api.ResourceRef;
import io.guidein.events.api.OutboxCommand;
import io.guidein.events.api.OutboxWriter;
import io.guidein.platform.api.ErrorCode;
import io.guidein.platform.api.GuideInException;
import io.guidein.platform.api.TenantContext;
import io.guidein.tenancy.api.CreateRepositoryCommand;
import io.guidein.tenancy.api.Membership;
import io.guidein.tenancy.api.RepositoryAdministration;
import io.guidein.tenancy.api.RepositoryView;
import io.guidein.tenancy.api.ScopeMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class JdbcRepositoryAdministration implements RepositoryAdministration {
    private final JdbcClient jdbc;
    private final TenantContext context;
    private final AuthorizationService authorization;
    private final AuditLedger audit;
    private final OutboxWriter outbox;

    JdbcRepositoryAdministration(JdbcClient jdbc, TenantContext context, AuthorizationService authorization,
                                 AuditLedger audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.context = context;
        this.authorization = authorization;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public RepositoryView create(CreateRepositoryCommand command) {
        context.setAuthenticatedUser(command.subject().userId());
        Membership membership = jdbc.sql("""
                SELECT id, tenant_id, user_id, role, scope_mode FROM memberships
                 WHERE user_id=:userId AND tenant_id=:tenantId
                """).param("userId", command.subject().userId()).param("tenantId", command.tenantId())
                .query((rs, row) -> new Membership(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), AuthorizationRole.valueOf(rs.getString(4)),
                        ScopeMode.valueOf(rs.getString(5))))
                .optional().orElseThrow(() -> new GuideInException(ErrorCode.RESOURCE_NOT_FOUND));
        context.setTenant(command.tenantId());
        AccessContext access = new AccessContext(membership.id(), membership.tenantId(), membership.userId(), membership.role());
        authorization.require(command.subject(), access, Capability.REPOSITORY_MANAGE,
                new ResourceRef(ResourceRef.ResourceType.REPOSITORY, command.tenantId(), command.repositoryId()),
                membership.scopeMode() == ScopeMode.ALL_REPOSITORIES);
        jdbc.sql("""
                INSERT INTO repositories(id, tenant_id, provider, external_id, owner, name, status)
                VALUES (:id, :tenantId, 'GITHUB', :externalId, :owner, :name, 'ACTIVE')
                """).param("id", command.repositoryId()).param("tenantId", command.tenantId())
                .param("externalId", command.externalId()).param("owner", command.owner())
                .param("name", command.name()).update();
        Instant now = Instant.now();
        audit.append(new AuditCommand(command.tenantId(), AuditCommand.ActorType.USER, command.subject().userId(),
                "repository.create", "REPOSITORY", command.repositoryId(), command.correlationId(), now,
                Map.of("provider", "GITHUB", "owner", command.owner(), "name", command.name())));
        outbox.append(new OutboxCommand(command.tenantId(), "REPOSITORY", command.repositoryId(),
                "repository.created", 1, Map.of("repository_id", command.repositoryId().toString()),
                command.correlationId(), null, now));
        return new RepositoryView(command.repositoryId(), command.tenantId(), "GITHUB", command.owner(), command.name(), "ACTIVE");
    }
}
