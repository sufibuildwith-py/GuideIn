package io.guidein.github.application;

import io.guidein.github.api.GitHubIntegration;
import io.guidein.github.infrastructure.client.*;
import io.guidein.github.infrastructure.webhook.WebhookAuthentication;
import io.guidein.identity.api.AuthenticatedSubject;
import io.guidein.platform.api.*;
import io.guidein.tenancy.api.IntegrationAccess;
import io.guidein.jobs.api.*;
import io.guidein.events.api.*;
import io.guidein.audit.api.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class GitHubIngestionService implements GitHubIntegration {
    static final String JOB="GITHUB_PROCESS_DELIVERY";
    private final GitHubStore store;
    private final GitHubProviderClient provider;
    private final WebhookAuthentication authentication;
    private final IntegrationAccess access;
    private final JobQueue jobs;
    private final OutboxWriter outbox;
    private final AuditLedger audit;
    private final GitHubHydrator hydrator;
    private final String appSlug,clientId,callback;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public GitHubIngestionService(GitHubStore store,GitHubProviderClient provider,WebhookAuthentication authentication,
                                  IntegrationAccess access,JobQueue jobs,OutboxWriter outbox,AuditLedger audit,
                                  GitHubHydrator hydrator,String appSlug,String clientId,String callback) {
        this.store=store;this.provider=provider;this.authentication=authentication;this.access=access;
        this.jobs=jobs;this.outbox=outbox;this.audit=audit;this.hydrator=hydrator;this.appSlug=appSlug;this.clientId=clientId;this.callback=callback;
        if(!appSlug.matches("[a-z0-9-]{1,100}")) throw new IllegalArgumentException("Invalid GitHub App slug");
    }
    public Preparation prepare(AuthenticatedSubject subject,UUID tenant) {
        String state=random(),verifier=random(),challenge=digestText(verifier);
        store.transaction(tenant,()->{
            access.requireWrite(subject,tenant);
            store.jdbc.sql("INSERT INTO github_binding_states(state_hash,tenant_id,user_id,pkce_challenge,expires_at) VALUES (:hash,:tenant,:user,:challenge,clock_timestamp()+interval '10 minutes')")
                    .param("hash",hash(state)).param("tenant",tenant).param("user",subject.userId()).param("challenge",challenge).update();return null;
        });
        String authorization="https://github.com/login/oauth/authorize?client_id="+url(clientId)+"&redirect_uri="+url(callback)
                +"&state="+state+"&code_challenge_method=S256&code_challenge="+challenge;
        return new Preparation("https://github.com/apps/"+appSlug+"/installations/new?state="+state,authorization,state,verifier);
    }
    public UUID bind(AuthenticatedSubject subject,UUID tenant,String state,String verifier,String code,long installation) {
        if(state==null || !state.matches("[A-Za-z0-9_-]{43}") || verifier==null || !verifier.matches("[A-Za-z0-9_-]{43}")
                || code==null || code.length()>1024 || installation<1) throw new GuideInException(ErrorCode.VALIDATION_FAILED);
        // Consume before the external exchange: callbacks cannot race; failed exchange requires a new preparation.
        store.transaction(tenant,()->{
            access.requireWrite(subject,tenant);
            int consumed=store.jdbc.sql("""
                    UPDATE github_binding_states SET consumed_at=clock_timestamp()
                    WHERE state_hash=:hash AND tenant_id=:tenant AND user_id=:user AND consumed_at IS NULL
                      AND expires_at>clock_timestamp() AND pkce_challenge=:challenge
                    """).param("hash",hash(state)).param("tenant",tenant).param("user",subject.userId()).param("challenge",digestText(verifier)).update();
            if(consumed!=1) throw new GuideInException(ErrorCode.AUTHORIZATION_DENIED);return null;
        });
        provider.verifyUserInstallation(code,verifier,installation);
        var canonical=provider.appInstallation(installation).json();
        if(canonical.path("id").asLong()!=installation || canonical.path("suspended_at").isTextual()
                || !canonical.path("app_slug").asText().equals(appSlug)) throw new GuideInException(ErrorCode.AUTHORIZATION_DENIED);
        var permissions=canonical.path("permissions");
        for(var entry:GitHubProviderClient.READ_PERMISSIONS.entrySet())
            if(!permissions.path(entry.getKey()).asText().equals(entry.getValue())) throw new GuideInException(ErrorCode.AUTHORIZATION_DENIED);
        UUID id=UUID.randomUUID(),correlation=UUID.randomUUID();
        return store.transaction(tenant,()->{
            access.requireWrite(subject,tenant);
            int routed=store.jdbc.sql("INSERT INTO github_installation_routes(installation_external_id,tenant_id,installation_id) VALUES (:external,:tenant,:id) ON CONFLICT DO NOTHING")
                    .param("external",installation).param("tenant",tenant).param("id",id).update();
            if(routed!=1) throw new GuideInException(ErrorCode.CONFLICT);
            store.jdbc.sql("""
                    INSERT INTO github_installations(id,tenant_id,installation_external_id,account_external_id,account_login,permissions_json,status,last_verified_at)
                    VALUES (:id,:tenant,:external,:account,:login,CAST(:permissions AS jsonb),'PENDING_BINDING',clock_timestamp())
                    """).param("id",id).param("tenant",tenant).param("external",installation)
                    .param("account",canonical.path("account").path("id").asLong()).param("login",canonical.path("account").path("login").asText())
                    .param("permissions",permissions.toString()).update();
            var route=new GitHubStore.Route(tenant,id,installation);
            UUID receiptId=UUID.randomUUID();
            store.jdbc.sql("""
                    INSERT INTO github_deliveries(id,external_delivery_id,hook_id,installation_external_id,event_type,action,payload_hash,signature_key_version,selectors,status,correlation_id)
                    VALUES (:id,:id,1,:installation,'reconcile','',:hash,'INTERNAL','{}','QUEUED',:correlation)
                    """).param("id",receiptId).param("installation",installation).param("hash",hash("binding:"+id)).param("correlation",correlation).update();
            enqueue(route,receiptId,correlation);
            audit(tenant,subject.userId(),id,"github.installation.bound",correlation);
            emit(tenant,id,"github.installation.bound",Map.of("installation_id",id),correlation,receiptId);
            return id;
        });
    }
    public Receipt accept(byte[] raw,String signature,String delivery,String hook,String event,UUID correlation) {
        String key=authentication.verify(raw,signature);
        UUID external;
        long hookId;
        try {
            if(delivery==null || !delivery.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException();
            external=UUID.fromString(delivery);hookId=Long.parseLong(hook);if(hookId<1) throw new IllegalArgumentException();
        } catch(RuntimeException ignored) {throw new GuideInException(ErrorCode.VALIDATION_FAILED);}
        var notification=GitHubNotification.parse(raw,event);
        var route=store.route(notification.installation());
        byte[] payloadHash=Digests.sha256(raw);
        Receipt result=store.transaction(route==null?null:route.tenant(),()->{
            UUID id=UUID.randomUUID();
            String status=!notification.supported()?"IGNORED":route==null?"AWAITING_BINDING":"QUEUED";
            int inserted=store.jdbc.sql("""
                    INSERT INTO github_deliveries(id,external_delivery_id,hook_id,installation_external_id,repository_external_id,event_type,action,
                      payload_hash,signature_key_version,selectors,status,correlation_id)
                    VALUES (:id,:external,:hook,:installation,:repository,:event,:action,:hash,:key,CAST(:selectors AS jsonb),:status,:correlation)
                    ON CONFLICT (provider,external_delivery_id) DO NOTHING
                    """).param("id",id).param("external",external).param("hook",hookId).param("installation",notification.installation())
                    .param("repository",notification.repository()).param("event",event).param("action",notification.action())
                    .param("hash",payloadHash).param("key",key).param("selectors",mapper.writeValueAsString(notification.selectors()))
                    .param("status",status).param("correlation",correlation).update();
            if(inserted==0) {
                return store.jdbc.sql("SELECT id,status,payload_hash,event_type,action FROM github_deliveries WHERE provider='GITHUB' AND external_delivery_id=:id")
                        .param("id",external).query((rs,n)->{
                            if(!java.security.MessageDigest.isEqual(payloadHash,rs.getBytes(3)) || !event.equals(rs.getString(4)) || !notification.action().equals(rs.getString(5)))
                                throw new GuideInException(ErrorCode.IDEMPOTENCY_CONFLICT);
                            return new Receipt(rs.getObject(1,UUID.class),rs.getString(2),true);
                        }).single();
            }
            if(route!=null && notification.supported()) {
                // Revocations are safe restrictive hints; granting authority always needs provider reconciliation.
                String restriction=notification.event().equals("installation")?switch(notification.action()){
                    case "deleted"->"DELETED";case "suspend"->"SUSPENDED";case "new_permissions_accepted"->"ACCESS_REDUCED";default->null;
                }:notification.event().equals("installation_repositories") && notification.action().equals("removed")?"ACCESS_REDUCED":null;
                if(restriction!=null) {
                    store.jdbc.sql("UPDATE github_installations SET status=:status,generation=generation+1,updated_at=clock_timestamp() WHERE id=:id AND status<>'DELETED'")
                            .param("status",restriction).param("id",route.id()).update();
                    if(restriction.equals("ACCESS_REDUCED")) store.jdbc.sql("UPDATE github_installation_repositories SET status='ACCESS_REMOVED' WHERE installation_id=:id")
                            .param("id",route.id()).update();
                    audit(route.tenant(),null,route.id(),"github.installation."+restriction.toLowerCase(Locale.ROOT),correlation);
                }
                enqueue(route,id,correlation);
                emit(route.tenant(),id,"github.delivery.accepted",Map.of("delivery_id",id),correlation,id);
            }
            return new Receipt(id,status,false);
        });
        if(!result.duplicate() && (event.equals("installation") || event.equals("installation_repositories"))) provider.evict(notification.installation());
        return result;
    }
    private void enqueue(GitHubStore.Route route,UUID receipt,UUID correlation) {
        UUID job=jobs.enqueue(new JobCommand(route.tenant(),JOB,"github:delivery:"+receipt,Map.of("delivery_id",receipt.toString()),
                Instant.now(),5,correlation,receipt));
        store.jdbc.sql("UPDATE github_deliveries SET status='QUEUED',job_id=:job,failure_code=NULL WHERE id=:id")
                .param("job",job).param("id",receipt).update();
    }
    public void redrive(AuthenticatedSubject subject,UUID tenant,UUID receipt,UUID correlation) {
        store.transaction(tenant,()->{
            access.requireWrite(subject,tenant);
            var route=store.jdbc.sql("""
                    SELECT r.installation_id,r.installation_external_id FROM github_deliveries d
                    JOIN github_installation_routes r ON r.installation_external_id=d.installation_external_id
                    WHERE d.id=:id AND r.tenant_id=:tenant AND d.status IN ('DEAD','RETRYABLE_FAILURE') FOR UPDATE OF d
                    """).param("id",receipt).param("tenant",tenant)
                    .query((rs,n)->new GitHubStore.Route(tenant,rs.getObject(1,UUID.class),rs.getLong(2))).optional()
                    .orElseThrow(()->new GuideInException(ErrorCode.RESOURCE_NOT_FOUND));
            enqueue(route,receipt,correlation);audit(tenant,subject.userId(),receipt,"github.delivery.redrive",correlation);return null;
        });
    }
    public boolean processNext(UUID tenant) {return hydrator.processNext(tenant);}
    private void audit(UUID tenant,UUID user,UUID id,String action,UUID correlation) {
        audit.append(new AuditCommand(tenant,user==null?AuditCommand.ActorType.SYSTEM:AuditCommand.ActorType.USER,user,action,"GITHUB_INSTALLATION",id,correlation,Instant.now(),Map.of()));
    }
    private void emit(UUID tenant,UUID id,String type,Map<String,Object> payload,UUID correlation,UUID cause) {
        outbox.append(new OutboxCommand(tenant,"GITHUB",id,type,1,payload,correlation,cause,Instant.now()));
    }
    private static String random() {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static byte[] hash(String text) {return Digests.sha256(text.getBytes(StandardCharsets.UTF_8));}
    private static String digestText(String text) {return Base64.getUrlEncoder().withoutPadding().encodeToString(hash(text));}
    private static String url(String text) {return java.net.URLEncoder.encode(text,StandardCharsets.UTF_8);}
}
