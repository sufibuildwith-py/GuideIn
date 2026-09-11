package io.guidein.authorization.api;

import io.guidein.identity.api.AuthenticatedSubject;

public interface AuthorizationService {
    AccessDecision decide(AuthenticatedSubject subject, AccessContext access, Capability capability,
                          ResourceRef resource, boolean resourceInScope);

    void require(AuthenticatedSubject subject, AccessContext access, Capability capability,
                 ResourceRef resource, boolean resourceInScope);
}
