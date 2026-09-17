package io.guidein.github.infrastructure;

import io.guidein.github.api.GitHubIntegration;
import io.guidein.github.api.RepositoryMaterialSource;
import io.guidein.github.application.*;
import io.guidein.github.infrastructure.auth.*;
import io.guidein.github.infrastructure.client.*;
import io.guidein.github.infrastructure.webhook.WebhookAuthentication;
import io.guidein.platform.api.TenantContext;
import io.guidein.tenancy.api.IntegrationAccess;
import io.guidein.jobs.api.JobQueue;
import io.guidein.events.api.OutboxWriter;
import io.guidein.audit.api.AuditLedger;
import io.guidein.change.api.ChangeWriter;
import io.guidein.provenance.api.ProvenanceWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="guidein.github.enabled",havingValue="true")
public class GitHubConfiguration {
    @Bean RepositoryMaterialSource repositoryMaterialSource(GitHubStore store, GitHubProviderClient provider) {
        return new GitHubRepositoryMaterialSource(store, provider);
    }
    @Bean GitHubStore gitHubStore(JdbcClient jdbc,TenantContext context,PlatformTransactionManager manager) {return new GitHubStore(jdbc,context,manager);}
    @Bean(destroyMethod="close") GitHubTransport githubApiTransport(Environment e) {
        return new GitHubTransport(URI.create(e.getProperty("guidein.github.api-origin","https://api.github.com")),Duration.ofSeconds(2),Duration.ofSeconds(5),
                5_242_880,testLoopback(e),Clock.systemUTC());
    }
    @Bean(destroyMethod="close") GitHubTransport githubOauthTransport(Environment e) {
        return new GitHubTransport(URI.create(e.getProperty("guidein.github.oauth-origin","https://github.com")),Duration.ofSeconds(2),Duration.ofSeconds(5),
                65_536,testLoopback(e),Clock.systemUTC());
    }
    private boolean testLoopback(Environment e) {
        boolean enabled=e.getProperty("guidein.github.allow-loopback-test",Boolean.class,false);
        if(enabled && !Arrays.asList(e.getActiveProfiles()).contains("test")) throw new IllegalArgumentException("Loopback provider requires test profile");
        return enabled;
    }
    @Bean GitHubAppJwtSigner githubSigner(Environment e) {
        return new PemGitHubAppJwtSigner(Path.of(e.getRequiredProperty("guidein.github.private-key-path")),e.getRequiredProperty("guidein.github.client-id"),Clock.systemUTC());
    }
    @Bean WebhookAuthentication githubAuthentication(Environment e) {
        List<GitHubWebhookSecretProvider.VersionedSecret> keys=new ArrayList<>();
        keys.add(new GitHubWebhookSecretProvider.VersionedSecret(e.getProperty("guidein.github.secret-version","current"),
                e.getRequiredProperty("guidein.github.webhook-secret").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String previous=e.getProperty("guidein.github.previous-webhook-secret");
        if(previous!=null) keys.add(new GitHubWebhookSecretProvider.VersionedSecret(e.getRequiredProperty("guidein.github.previous-secret-version"),previous.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new WebhookAuthentication(()->List.copyOf(keys));
    }
    @Bean GitHubProviderClient githubProvider(GitHubTransport githubApiTransport,GitHubTransport githubOauthTransport,GitHubAppJwtSigner signer,GitHubStore store,Environment e) {
        return new GitHubProviderClient(githubApiTransport,githubOauthTransport,signer,Clock.systemUTC(),e.getRequiredProperty("guidein.github.client-id"),
                e.getRequiredProperty("guidein.github.client-secret"),e.getRequiredProperty("guidein.github.callback-url"),store);
    }
    @Bean GitHubHydrator githubHydrator(GitHubStore store,GitHubProviderClient provider,JobQueue jobs,IntegrationAccess repositories,
                                      ChangeWriter changes,ProvenanceWriter provenance,OutboxWriter outbox) {
        return new GitHubHydrator(store,provider,jobs,repositories,changes,provenance,outbox);
    }
    @Bean GitHubIntegration githubIntegration(GitHubStore store,GitHubProviderClient provider,WebhookAuthentication authentication,
                                             IntegrationAccess access,JobQueue jobs,OutboxWriter outbox,AuditLedger audit,GitHubHydrator hydrator,Environment e) {
        return new GitHubIngestionService(store,provider,authentication,access,jobs,outbox,audit,hydrator,
                e.getRequiredProperty("guidein.github.app-slug"),e.getRequiredProperty("guidein.github.client-id"),e.getRequiredProperty("guidein.github.callback-url"));
    }
}
