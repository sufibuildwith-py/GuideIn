package io.guidein.web;

import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.identity.api.IdentityResolver;
import io.guidein.tenancy.api.RepositoryQuery;
import io.guidein.tenancy.api.RepositoryView;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class ApiController {
    private final IdentityResolver identities;
    private final RepositoryQuery repositories;

    ApiController(IdentityResolver identities, RepositoryQuery repositories) {
        this.identities = identities;
        this.repositories = repositories;
    }

    @GetMapping("/me")
    Map<String, Object> me(Jwt jwt) {
        AuthenticatedSubject subject = subject(jwt);
        return Map.of("id", subject.userId(), "issuer", subject.issuer(), "subject", subject.externalSubject());
    }

    @GetMapping("/tenants/{tenantId}/repositories/{repositoryId}")
    RepositoryView repository(Jwt jwt, @PathVariable UUID tenantId, @PathVariable UUID repositoryId) {
        return repositories.get(subject(jwt), tenantId, repositoryId);
    }

    private AuthenticatedSubject subject(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        return identities.resolve(issuer, jwt.getSubject(), jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"));
    }
}
