package io.guidein.authorization.application;

import io.guidein.authorization.api.AccessDecision;
import io.guidein.authorization.api.AccessContext;
import io.guidein.authorization.api.AuthorizationService;
import io.guidein.authorization.api.AuthorizationRole;
import io.guidein.authorization.api.Capability;
import io.guidein.authorization.api.ResourceRef;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.observability.api.KernelMetrics;
import io.guidein.platform.api.ErrorCode;
import io.guidein.platform.api.GuideInException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
final class CapabilityAuthorizationService implements AuthorizationService {
    private static final Map<AuthorizationRole, EnumSet<Capability>> MATRIX = matrix();
    private final KernelMetrics metrics;

    CapabilityAuthorizationService(KernelMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public AccessDecision decide(AuthenticatedSubject subject, AccessContext access, Capability capability,
                                 ResourceRef resource, boolean resourceInScope) {
        if (subject == null || access == null || access.role() == null || capability == null || resource == null) {
            return AccessDecision.deny("AUTHORIZATION_INPUT_MISSING");
        }
        if (!subject.userId().equals(access.userId()) || !access.tenantId().equals(resource.tenantId())) {
            return AccessDecision.deny("TENANT_ACCESS_DENIED");
        }
        if (!MATRIX.getOrDefault(access.role(), EnumSet.noneOf(Capability.class)).contains(capability)) {
            return AccessDecision.deny("CAPABILITY_DENIED");
        }
        if ((capability == Capability.REPOSITORY_READ || capability == Capability.REPOSITORY_MANAGE)
                && !resourceInScope) {
            return AccessDecision.deny("RESOURCE_SCOPE_DENIED");
        }
        return AccessDecision.allow();
    }

    @Override
    public void require(AuthenticatedSubject subject, AccessContext access, Capability capability,
                        ResourceRef resource, boolean resourceInScope) {
        AccessDecision decision = decide(subject, access, capability, resource, resourceInScope);
        if (!decision.allowed()) {
            metrics.authorizationDenied("TENANT_ACCESS_DENIED".equals(decision.reasonCode()));
            throw new GuideInException(ErrorCode.AUTHORIZATION_DENIED, decision.reasonCode());
        }
    }

    private static Map<AuthorizationRole, EnumSet<Capability>> matrix() {
        EnumMap<AuthorizationRole, EnumSet<Capability>> matrix = new EnumMap<>(AuthorizationRole.class);
        matrix.put(AuthorizationRole.OWNER, EnumSet.allOf(Capability.class));
        matrix.put(AuthorizationRole.ADMIN, EnumSet.of(Capability.TENANT_READ, Capability.TENANT_MEMBERS_READ,
                Capability.TENANT_MEMBERS_WRITE, Capability.REPOSITORY_READ, Capability.REPOSITORY_MANAGE,
                Capability.PLATFORM_READ));
        matrix.put(AuthorizationRole.SECURITY, EnumSet.of(Capability.TENANT_READ, Capability.TENANT_MEMBERS_READ,
                Capability.REPOSITORY_READ, Capability.REPOSITORY_MANAGE, Capability.AUDIT_READ,
                Capability.PLATFORM_READ));
        matrix.put(AuthorizationRole.RELEASE_MANAGER, EnumSet.of(Capability.TENANT_READ, Capability.REPOSITORY_READ,
                Capability.REPOSITORY_MANAGE, Capability.PLATFORM_READ));
        matrix.put(AuthorizationRole.ENGINEER, EnumSet.of(Capability.TENANT_READ, Capability.REPOSITORY_READ,
                Capability.PLATFORM_READ));
        matrix.put(AuthorizationRole.AUDITOR, EnumSet.of(Capability.TENANT_READ, Capability.REPOSITORY_READ,
                Capability.AUDIT_READ, Capability.PLATFORM_READ));
        matrix.put(AuthorizationRole.VIEWER, EnumSet.of(Capability.TENANT_READ, Capability.REPOSITORY_READ,
                Capability.PLATFORM_READ));
        return Map.copyOf(matrix);
    }
}
