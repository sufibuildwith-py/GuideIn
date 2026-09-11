package io.guidein.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.guidein.authorization.api.AccessContext;
import io.guidein.authorization.api.AuthorizationRole;
import io.guidein.authorization.api.Capability;
import io.guidein.authorization.api.ResourceRef;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.observability.api.KernelMetrics;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class AuthorizationMatrixTest {
    private final CapabilityAuthorizationService service =
            new CapabilityAuthorizationService(mock(KernelMetrics.class));

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @MethodSource("allCells")
    void everyRoleCapabilityCellIsExplicit(AuthorizationRole role, Capability capability, boolean expected) {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        AuthenticatedSubject subject = new AuthenticatedSubject(user, "https://issuer.example", "subject");
        AccessContext access = new AccessContext(UUID.randomUUID(), tenant, user, role);
        ResourceRef resource = new ResourceRef(ResourceRef.ResourceType.REPOSITORY, tenant, UUID.randomUUID());
        assertThat(service.decide(subject, access, capability, resource, true).allowed()).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource("roles")
    void tenantMismatchAlwaysDenies(AuthorizationRole role) {
        UUID user = UUID.randomUUID();
        AuthenticatedSubject subject = new AuthenticatedSubject(user, "https://issuer.example", "subject");
        AccessContext access = new AccessContext(UUID.randomUUID(), UUID.randomUUID(), user, role);
        ResourceRef resource = new ResourceRef(ResourceRef.ResourceType.REPOSITORY, UUID.randomUUID(), UUID.randomUUID());
        assertThat(service.decide(subject, access, Capability.REPOSITORY_READ, resource, true).allowed()).isFalse();
    }

    static Stream<Arguments> allCells() {
        return Stream.of(AuthorizationRole.values()).flatMap(role -> Stream.of(Capability.values())
                .map(capability -> Arguments.of(role, capability, allowed(role).contains(capability))));
    }

    static Stream<AuthorizationRole> roles() { return Stream.of(AuthorizationRole.values()); }

    private static Set<Capability> allowed(AuthorizationRole role) {
        return switch (role) {
            case OWNER -> Set.of(Capability.values());
            case ADMIN -> Set.of(Capability.TENANT_READ, Capability.TENANT_MEMBERS_READ,
                    Capability.TENANT_MEMBERS_WRITE, Capability.REPOSITORY_READ,
                    Capability.REPOSITORY_MANAGE, Capability.PLATFORM_READ);
            case SECURITY -> Set.of(Capability.TENANT_READ, Capability.TENANT_MEMBERS_READ,
                    Capability.REPOSITORY_READ, Capability.REPOSITORY_MANAGE,
                    Capability.AUDIT_READ, Capability.PLATFORM_READ);
            case RELEASE_MANAGER -> Set.of(Capability.TENANT_READ, Capability.REPOSITORY_READ,
                    Capability.REPOSITORY_MANAGE, Capability.PLATFORM_READ);
            case ENGINEER -> Set.of(Capability.TENANT_READ, Capability.REPOSITORY_READ, Capability.PLATFORM_READ);
            case AUDITOR -> Set.of(Capability.TENANT_READ, Capability.REPOSITORY_READ,
                    Capability.AUDIT_READ, Capability.PLATFORM_READ);
            case VIEWER -> Set.of(Capability.TENANT_READ, Capability.REPOSITORY_READ, Capability.PLATFORM_READ);
        };
    }
}

