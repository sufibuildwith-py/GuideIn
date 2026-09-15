package io.guidein.github.application;

import io.guidein.github.api.GitHubIntegration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name={"guidein.github.enabled","guidein.github.worker-enabled"},havingValue="true")
public final class GitHubWorker {
    private final GitHubStore store;
    private final GitHubIntegration integration;
    public GitHubWorker(GitHubStore store,GitHubIntegration integration) {this.store=store;this.integration=integration;}
    @Scheduled(fixedDelayString="${guidein.github.poll-delay-ms:1000}")
    public void tick() {
        var tenants=store.transaction(null,()->store.jdbc.sql("SELECT DISTINCT tenant_id FROM github_installation_routes ORDER BY tenant_id")
                .query(UUID.class).list());
        for(UUID tenant:tenants) {
            try {integration.processNext(tenant);}
            catch(RuntimeException ignored) { /* Durable queue owns recovery; scheduler never acknowledges failed work. */ }
        }
    }
}
