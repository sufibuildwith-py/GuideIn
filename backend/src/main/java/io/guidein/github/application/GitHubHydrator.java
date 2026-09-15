package io.guidein.github.application;

import io.guidein.github.infrastructure.client.*;
import io.guidein.jobs.api.*;
import io.guidein.change.api.*;
import io.guidein.provenance.api.ProvenanceWriter;
import io.guidein.tenancy.api.IntegrationAccess;
import io.guidein.events.api.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Queue orchestration only; durable ownership and acknowledgement stay in the Phase-1 queue. */
public final class GitHubHydrator {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GitHubHydrator.class);
    private final GitHubStore store;
    private final GitHubProviderClient provider;
    private final JobQueue jobs;
    private final IntegrationAccess repositories;
    private final ChangeWriter changes;
    private final ProvenanceWriter provenance;
    private final OutboxWriter outbox;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public GitHubHydrator(GitHubStore store,GitHubProviderClient provider,JobQueue jobs,IntegrationAccess repositories,
                          ChangeWriter changes,ProvenanceWriter provenance,OutboxWriter outbox) {
        this.store=store;this.provider=provider;this.jobs=jobs;this.repositories=repositories;this.changes=changes;this.provenance=provenance;this.outbox=outbox;
    }
    private record Work(GitHubStore.Route route,ClaimedJob job,UUID delivery,String event,String action,long externalRepo,
                        JsonNode selectors,long generation,UUID streamToken) { }
    public boolean processNext(UUID tenant) {
        Work work=store.transaction(tenant,()->claim(tenant));
        if(work==null) return false;
        try(var correlation=org.slf4j.MDC.putCloseable("correlation_id",work.job().correlationId().toString());
            var cause=org.slf4j.MDC.putCloseable("causation_id",work.delivery().toString());
            var job=org.slf4j.MDC.putCloseable("job_id",work.job().id().toString());
            var tenantLog=org.slf4j.MDC.putCloseable("tenant_id",tenant.toString())) {
        LOG.info("github_delivery_claimed");
        try {
            Runnable persistence=fetch(work);
            store.transaction(tenant,()->{
                var current=store.installation(work.route(),true);
                if(current.generation()!=work.generation()) throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
                int owned=store.jdbc.sql("SELECT count(*) FROM github_installations WHERE id=:id AND processing_token=:token AND processing_until>clock_timestamp()")
                        .param("id",work.route().id()).param("token",work.streamToken()).query(Integer.class).single();
                if(owned!=1) throw new ProviderFailure(FailureCategory.CONFLICT,null);
                // Guard first, in this same transaction: every normalized write rolls back if completion fails.
                if(!jobs.complete(tenant,work.job().id(),work.job().leaseToken())) throw new ProviderFailure(FailureCategory.CONFLICT,null);
                persistence.run();
                store.jdbc.sql("UPDATE github_deliveries SET status='PROCESSED',processed_at=clock_timestamp(),failure_code=NULL WHERE id=:id")
                        .param("id",work.delivery()).update();
                release(work);return null;
            });
            LOG.info("github_delivery_processed");
        } catch(RuntimeException failure) {
            ProviderFailure classified=failure instanceof ProviderFailure p?p:new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null);
            store.transaction(tenant,()->{
                try {
                    var terminal=jobs.failNotBefore(tenant,work.job().id(),work.job().leaseToken(),classified.category(),classified.getMessage(),classified.retryAt());
                    store.jdbc.sql("UPDATE github_deliveries SET status=:status,failure_code=:code WHERE id=:id")
                            .param("status",terminal==JobStatus.READY?"RETRYABLE_FAILURE":"DEAD").param("code",classified.getMessage()).param("id",work.delivery()).update();
                    if(classified.category()==FailureCategory.RATE_LIMIT) store.jdbc.sql("UPDATE github_installations SET cooldown_until=GREATEST(cooldown_until,:until) WHERE id=:id")
                            .param("until",java.sql.Timestamp.from(classified.retryAt()==null?Instant.now().plusSeconds(60):classified.retryAt())).param("id",work.route().id()).update();
                } catch(io.guidein.platform.api.GuideInException staleLease) { /* Another owner alone may update delivery outcome. */ }
                release(work);return null;
            });
            LOG.warn("github_delivery_failed category={}",classified.category());
        }
        }
        return true;
    }
    private Work claim(UUID tenant) {
        // Promote late pre-binding receipts too, closing the binding-versus-ingress race.
        var staged=store.jdbc.sql("""
                SELECT d.id FROM github_deliveries d JOIN github_installation_routes r ON r.installation_external_id=d.installation_external_id
                WHERE r.tenant_id=:tenant AND d.status='AWAITING_BINDING' ORDER BY d.received_at LIMIT 100 FOR UPDATE OF d SKIP LOCKED
                """).param("tenant",tenant).query(UUID.class).list();
        for(UUID id:staged) {
            UUID job=jobs.enqueue(new JobCommand(tenant,GitHubIngestionService.JOB,"github:delivery:"+id,Map.of("delivery_id",id.toString()),Instant.now(),5,id,id));
            store.jdbc.sql("UPDATE github_deliveries SET status='QUEUED',job_id=:job WHERE id=:id").param("job",job).param("id",id).update();
        }
        var candidate=store.jdbc.sql("""
                SELECT r.installation_id,r.installation_external_id FROM job_queue j
                JOIN github_deliveries d ON d.job_id=j.id JOIN github_installation_routes r ON r.installation_external_id=d.installation_external_id
                WHERE j.tenant_id=:tenant AND r.tenant_id=:tenant AND j.job_type=:type AND j.attempt_count<j.max_attempts
                  AND ((j.status='READY' AND j.available_at<=clock_timestamp()) OR (j.status='RUNNING' AND j.lease_until<=clock_timestamp()))
                ORDER BY j.available_at,j.id LIMIT 1
                """).param("tenant",tenant).param("type",GitHubIngestionService.JOB)
                .query((rs,n)->new GitHubStore.Route(tenant,rs.getObject(1,UUID.class),rs.getLong(2))).optional();
        if(candidate.isEmpty()) {
            // Let the proven queue retire exhausted leases even when there is no remaining eligible candidate.
            jobs.claimNext(tenant,GitHubIngestionService.JOB);
            store.jdbc.sql("UPDATE github_deliveries d SET status='DEAD',failure_code='LEASE_EXHAUSTED' FROM job_queue j WHERE d.job_id=j.id AND j.tenant_id=:tenant AND j.status='DEAD' AND d.status<>'PROCESSED'")
                    .param("tenant",tenant).update();
            return null;
        }
        var route=candidate.get(); UUID stream=UUID.randomUUID();
        int reserved=store.jdbc.sql("""
                UPDATE github_installations SET processing_token=:token,processing_until=clock_timestamp()+interval '25 seconds'
                WHERE id=:id AND (processing_until IS NULL OR processing_until<=clock_timestamp())
                  AND (cooldown_until IS NULL OR cooldown_until<=clock_timestamp())
                """).param("token",stream).param("id",route.id()).update();
        if(reserved==0) return null;
        var claimed=jobs.claimNext(tenant,GitHubIngestionService.JOB);
        if(claimed.isEmpty()) {
            store.jdbc.sql("UPDATE github_installations SET processing_token=NULL,processing_until=NULL WHERE id=:id AND processing_token=:token")
                    .param("id",route.id()).param("token",stream).update();return null;
        }
        var job=claimed.get();
        var work=store.jdbc.sql("SELECT id,event_type,action,repository_external_id,selectors::text,installation_external_id FROM github_deliveries WHERE job_id=:job")
                .param("job",job.id()).query((rs,n)->{
                    if(rs.getLong(6)!=route.external()) throw new ProviderFailure(FailureCategory.CONFLICT,null);
                    return new Work(route,job,rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4),mapper.readTree(rs.getString(5)),store.installation(route,false).generation(),stream);
                }).single();
        store.jdbc.sql("UPDATE github_deliveries SET status='PROCESSING' WHERE id=:id").param("id",work.delivery()).update();
        return work;
    }
    private void release(Work work) {
        store.jdbc.sql("UPDATE github_installations SET processing_token=NULL,processing_until=NULL WHERE id=:id AND processing_token=:token")
                .param("id",work.route().id()).param("token",work.streamToken()).update();
    }
    private Runnable fetch(Work w) {
        Instant deadline=Instant.now().plusSeconds(20);
        if(w.event().equals("installation") && Set.of("suspend","deleted").contains(w.action())) return ()->{};
        if(w.event().equals("reconcile") || w.event().startsWith("installation")) return sync(w,deadline);
        var repo=store.transaction(w.route().tenant(),()->{
            if(!store.installation(w.route(),false).status().equals("ACTIVE")) throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
            return store.jdbc.sql("""
                    SELECT repository_id FROM github_installation_repositories
                    WHERE tenant_id=:tenant AND installation_id=:installation AND repository_external_id=:external AND status='ACTIVE'
                    """).param("tenant",w.route().tenant()).param("installation",w.route().id()).param("external",w.externalRepo())
                    .query(UUID.class).optional().orElseThrow(()->new ProviderFailure(FailureCategory.AUTHORIZATION,null));
        });
        // Numeric identity is checked again before using provider display coordinates for subsequent calls.
        var metadata=get(w,"/repositories/"+w.externalRepo(),deadline).json();
        if(metadata.path("id").asLong()!=w.externalRepo()) throw new ProviderFailure(FailureCategory.STALE_SOURCE,null);
        String owner=coordinate(metadata.path("owner").path("login").asText()),name=coordinate(metadata.path("name").asText());
        String prefix="/repos/"+owner+"/"+name;
        if(w.event().equals("status") || w.event().equals("check_run")) {
            String sha=GitHubNotification.sha(w.selectors().path("sha").asText());
            var ci=ci(w,prefix,sha,deadline);
            return ()->changes.observeCi(w.route().tenant(),repo,sha,ci,w.job().correlationId(),w.delivery());
        }
        String head,base,external,type,state,title=null,ref=null;
        Integer reported=null;String fileStatus="UNKNOWN",reason="Provider completeness is not established";
        Instant updated=null; boolean forced=false;
        List<ChangeCandidate.FileMetadata> files=new ArrayList<>();
        if(w.event().equals("pull_request")) {
            long number=w.selectors().path("number").asLong();
            var pr=get(w,prefix+"/pulls/"+number,deadline).json();
            if(pr.path("number").asLong()!=number || pr.path("base").path("repo").path("id").asLong()!=w.externalRepo())
                throw new ProviderFailure(FailureCategory.STALE_SOURCE,null);
            head=GitHubNotification.sha(pr.path("head").path("sha").asText());base=GitHubNotification.sha(pr.path("base").path("sha").asText());
            external="pr:"+number;type="PULL_REQUEST";state=pr.path("state").asText();title=pr.path("title").asText();updated=time(pr.path("updated_at"));
            reported=pr.path("changed_files").isIntegralNumber()?pr.path("changed_files").asInt():null;
            var pages=pages(w,prefix+"/pulls/"+number+"/files?per_page=100",null,30,deadline);
            for(var file:pages.rows()) files.add(file(file));
            fileStatus=reported!=null && reported==files.size() && pages.complete()?"COMPLETE":
                    reported!=null && (reported>3000 || reported>files.size())?"INCOMPLETE_PROVIDER_LIMIT":!pages.complete()?"INCOMPLETE_PAGE_BUDGET":"UNKNOWN";
            reason=fileStatus.equals("COMPLETE")?null:"Provider file listing incomplete or count unavailable";
            var recheck=get(w,prefix+"/pulls/"+number,deadline).json();
            if(!head.equals(recheck.path("head").path("sha").asText()) || !base.equals(recheck.path("base").path("sha").asText()))
                throw new ProviderFailure(FailureCategory.TRANSIENT_PROVIDER,null);
        } else {
            head=GitHubNotification.sha(w.selectors().path("after").asText());base=GitHubNotification.sha(w.selectors().path("before").asText());
            ref=w.selectors().path("ref").asText();forced=w.selectors().path("forced").asBoolean();
            boolean deletion=w.selectors().path("deleted").asBoolean();
            if(deletion!=head.matches("0+")) throw new ProviderFailure(FailureCategory.INVALID_INPUT,null);
            type=deletion?"BRANCH_DELETION":"DIRECT_PUSH";state=deletion?"DELETED":"OBSERVED";
            external="push:"+ref+":"+base+":"+head;
            if(deletion) {fileStatus="NOT_APPLICABLE";reason="Branch deletion notification; no head commit";}
        }
        boolean deletion=type.equals("BRANCH_DELETION");
        GitHubProviderClient.Document commit=deletion?null:get(w,prefix+"/commits/"+head,deadline);
        if(commit!=null && !head.equals(commit.json().path("sha").asText())) throw new ProviderFailure(FailureCategory.STALE_SOURCE,null);
        if(type.equals("DIRECT_PUSH")) for(var f:commit.json().path("files")) files.add(file(f));
        var ci=deletion?List.<ChangeWriter.CiObservation>of():ci(w,prefix,head,deadline);
        var candidate=new ChangeCandidate(w.route().tenant(),repo,"GITHUB",external,type,base,head,ref,forced,title,state,reported,fileStatus,reason,updated,files);
        return ()->{
            repositories.reconcileRepository(w.route().tenant(),w.externalRepo(),owner,name);
            UUID change=changes.normalize(candidate,w.job().correlationId(),w.delivery());
            if(commit!=null) {
                var verification=commit.json().path("commit").path("verification");
                var actor=commit.json().path("author");
                String actorType=actor.path("login").asText().equals("ghost")?"GHOST":switch(actor.path("type").asText()) {
                    case "User" -> actor.path("id").asLong()>0?"USER":"UNKNOWN";
                    case "Bot" -> "BOT";
                    case "App" -> "APP";
                    case "System" -> "SYSTEM";
                    default -> "UNKNOWN";
                };
                provenance.append(new ProvenanceWriter.Observation(w.route().tenant(),change,"GITHUB",w.externalRepo(),w.route().external(),candidate.headSha(),
                        actor.path("id").isNumber()?actor.path("id").asText():null,actorType,verification.path("verified").isBoolean()?verification.path("verified").asBoolean():null,
                        nullable(verification.path("reason")),time(verification.path("verified_at")),commit.requestId(),true,"PROVIDER_API_SOURCE_OBSERVATION"),w.job().correlationId(),w.delivery());
            }
            changes.observeCi(w.route().tenant(),repo,candidate.headSha(),ci,w.job().correlationId(),w.delivery());
        };
    }
    private Runnable sync(Work w,Instant deadline) {
        var canonical=provider.appInstallation(w.route().external()).json();
        if(canonical.path("id").asLong()!=w.route().external()) throw new ProviderFailure(FailureCategory.AUTHORIZATION,null);
        if(canonical.path("suspended_at").isTextual()) return ()->{
            store.jdbc.sql("UPDATE github_installations SET status='SUSPENDED',generation=generation+1 WHERE id=:id").param("id",w.route().id()).update();provider.evict(w.route().external());
        };
        for(var permission:GitHubProviderClient.READ_PERMISSIONS.entrySet()) if(!canonical.path("permissions").path(permission.getKey()).asText().equals(permission.getValue()))
            return ()->{store.jdbc.sql("UPDATE github_installations SET status='PERMISSION_UPDATE_PENDING',generation=generation+1 WHERE id=:id").param("id",w.route().id()).update();provider.evict(w.route().external());};
        // Unsuspension requires the app-level canonical observation before token mint is enabled.
        store.transaction(w.route().tenant(),()->{
            store.jdbc.sql("UPDATE github_installations SET status='PENDING_BINDING' WHERE id=:id AND generation=:generation AND status='SUSPENDED'")
                    .param("id",w.route().id()).param("generation",w.generation()).update();return null;
        });
        var repos=pages(w,"/installation/repositories?per_page=100","repositories",100,deadline);
        if(!repos.complete()) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null);
        return ()->{
            store.jdbc.sql("UPDATE github_installation_repositories SET status='ACCESS_REMOVED' WHERE installation_id=:id").param("id",w.route().id()).update();
            for(var repository:repos.rows()) {
                long external=repository.path("id").asLong();
                UUID id=repositories.reconcileRepository(w.route().tenant(),external,coordinate(repository.path("owner").path("login").asText()),coordinate(repository.path("name").asText()));
                store.jdbc.sql("""
                        INSERT INTO github_installation_repositories(tenant_id,installation_id,repository_id,repository_external_id,status,verified_at)
                        VALUES (:tenant,:installation,:id,:external,'ACTIVE',clock_timestamp())
                        ON CONFLICT (tenant_id,installation_id,repository_external_id) DO UPDATE SET status='ACTIVE',verified_at=EXCLUDED.verified_at
                        """).param("tenant",w.route().tenant()).param("installation",w.route().id()).param("id",id).param("external",external).update();
            }
            store.jdbc.sql("UPDATE github_installations SET status='ACTIVE',account_login=:login,permissions_json=CAST(:permissions AS jsonb),last_verified_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=:id AND status<>'DELETED'")
                    .param("login",canonical.path("account").path("login").asText()).param("permissions",canonical.path("permissions").toString()).param("id",w.route().id()).update();
            outbox.append(new OutboxCommand(w.route().tenant(),"GITHUB_INSTALLATION",w.route().id(),"github.installation.updated",1,
                    Map.of("installation_id",w.route().id()),w.job().correlationId(),w.delivery(),Instant.now()));
            outbox.append(new OutboxCommand(w.route().tenant(),"GITHUB_INSTALLATION",w.route().id(),"github.repository.access_changed",1,
                    Map.of("installation_id",w.route().id()),w.job().correlationId(),w.delivery(),Instant.now()));
        };
    }
    private List<ChangeWriter.CiObservation> ci(Work w,String prefix,String sha,Instant deadline) {
        List<ChangeWriter.CiObservation> results=new ArrayList<>();
        var checks=pages(w,prefix+"/commits/"+sha+"/check-runs?per_page=100","check_runs",100,deadline);
        var statuses=pages(w,prefix+"/commits/"+sha+"/statuses?per_page=100",null,100,deadline);
        if(!checks.complete() || !statuses.complete()) throw new ProviderFailure(FailureCategory.DEPENDENCY_UNAVAILABLE,null);
        for(var check:checks.rows()) {
            if(!sha.equals(check.path("head_sha").asText())) throw new ProviderFailure(FailureCategory.STALE_SOURCE,null);
            results.add(new ChangeWriter.CiObservation("GITHUB","CHECK_RUN",check.path("id").asText(),check.path("app").path("id").asText("UNKNOWN"),
                    check.path("name").asText(),check.path("status").asText(),nullable(check.path("conclusion")),time(check.path("started_at")),time(check.path("completed_at")),nullable(check.path("details_url")),time(check.path("updated_at"))));
        }
        for(var status:statuses.rows()) results.add(new ChangeWriter.CiObservation("GITHUB","COMMIT_STATUS",status.path("id").asText(),
                status.path("creator").path("id").asText("UNKNOWN"),status.path("context").asText(),status.path("state").asText(),status.path("state").asText(),
                time(status.path("created_at")),time(status.path("updated_at")),nullable(status.path("target_url")),time(status.path("updated_at"))));
        return List.copyOf(results);
    }
    private GitHubProviderClient.Document get(Work w,String path,Instant deadline) {
        checkDeadline(deadline);var document=provider.get(w.route().external(),w.generation(),path);
        LOG.info("github_provider_fetch request_id={}",document.requestId());return document;
    }
    private GitHubProviderClient.PageSet pages(Work w,String path,String array,int pages,Instant deadline) {checkDeadline(deadline);var result=provider.pages(w.route().external(),w.generation(),path,array,pages,deadline);checkDeadline(deadline);return result;}
    private void checkDeadline(Instant deadline) {if(Instant.now().isAfter(deadline)) throw new ProviderFailure(FailureCategory.TIMEOUT,null);}
    private static String coordinate(String value) {if(!value.matches("[A-Za-z0-9_.-]{1,100}")) throw new ProviderFailure(FailureCategory.INVALID_INPUT,null);return value;}
    private static String nullable(JsonNode value) {return value.isMissingNode()||value.isNull()?null:value.asText();}
    private static Instant time(JsonNode value) {String text=nullable(value);return text==null?null:Instant.parse(text);}
    private static Integer count(JsonNode value) {return value.isIntegralNumber()?value.asInt():null;}
    private static ChangeCandidate.FileMetadata file(JsonNode f) {
        return new ChangeCandidate.FileMetadata(f.path("filename").asText(),nullable(f.path("previous_filename")),f.path("status").asText(),
                count(f.path("additions")),count(f.path("deletions")),count(f.path("changes")),nullable(f.path("sha")),null,null);
    }
}
