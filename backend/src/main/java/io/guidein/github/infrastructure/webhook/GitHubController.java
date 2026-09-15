package io.guidein.github.infrastructure.webhook;

import io.guidein.github.api.GitHubIntegration;
import io.guidein.github.infrastructure.client.ProviderFailure;
import io.guidein.identity.api.*;
import io.guidein.platform.api.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="guidein.github.enabled",havingValue="true")
public final class GitHubController {
    private final GitHubIntegration integration;
    private final IdentityResolver identities;
    private final int limit;
    public GitHubController(GitHubIntegration integration,IdentityResolver identities,@Value("${guidein.github.webhook.max-payload-bytes:26214400}") int limit) {
        this.integration=integration;this.identities=identities;this.limit=limit;
    }
    @PostMapping("/api/v1/webhooks/github")
    ResponseEntity<?> webhook(HttpServletRequest request) throws java.io.IOException {
        try {
            byte[] raw=WebhookAuthentication.readBounded(request.getInputStream(),limit);
            var identity=(RequestIdentity)request.getAttribute(RequestIdentity.class.getName());
            return ResponseEntity.ok(integration.accept(raw,header(request,"X-Hub-Signature-256"),header(request,"X-GitHub-Delivery"),
                    header(request,"X-GitHub-Hook-ID"),header(request,"X-GitHub-Event"),identity.correlationId()));
        } catch(WebhookAuthentication.InvalidSignature ignored) {return ResponseEntity.status(401).body(Map.of("code","INVALID_WEBHOOK_SIGNATURE"));}
        catch(WebhookAuthentication.PayloadTooLarge ignored) {return ResponseEntity.status(413).body(Map.of("code","PAYLOAD_TOO_LARGE"));}
    }
    @PostMapping("/api/v1/tenants/{tenant}/integrations/github/install")
    GitHubIntegration.Preparation prepare(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenant) {return integration.prepare(subject(jwt),tenant);}
    public record Callback(String state,String verifier,String code,long installationId) { }
    @PostMapping("/api/v1/tenants/{tenant}/integrations/github/callback")
    Map<String,UUID> callback(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenant,@RequestBody Callback callback) {
        return Map.of("installation_id",integration.bind(subject(jwt),tenant,callback.state(),callback.verifier(),callback.code(),callback.installationId()));
    }
    @PostMapping("/api/v1/tenants/{tenant}/integrations/github/deliveries/{delivery}/redrive")
    ResponseEntity<Void> redrive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID tenant,@PathVariable UUID delivery) {
        integration.redrive(subject(jwt),tenant,delivery,UUID.randomUUID());return ResponseEntity.accepted().build();
    }
    @ExceptionHandler(ProviderFailure.class)
    ResponseEntity<?> providerFailure(ProviderFailure failure) {
        return ResponseEntity.status(failure.category().retryable()?503:403).body(Map.of("code",failure.getMessage()));
    }
    private AuthenticatedSubject subject(Jwt jwt) {return identities.resolve(jwt.getIssuer().toString(),jwt.getSubject(),jwt.getClaimAsString("email"),jwt.getClaimAsString("name"));}
    private String header(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()!=1) throw new WebhookAuthentication.InvalidSignature();
        return values.getFirst();
    }
}
