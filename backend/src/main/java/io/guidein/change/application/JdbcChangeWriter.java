package io.guidein.change.application;

import io.guidein.change.api.*;
import io.guidein.events.api.*;
import io.guidein.platform.api.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class JdbcChangeWriter implements ChangeWriter {
    private final JdbcClient jdbc;
    private final TenantContext context;
    private final OutboxWriter outbox;
    JdbcChangeWriter(JdbcClient jdbc,TenantContext context,OutboxWriter outbox) {this.jdbc=jdbc;this.context=context;this.outbox=outbox;}
    @Transactional public UUID normalize(ChangeCandidate c,UUID correlation,UUID causation) {
        context.setTenant(c.tenantId());
        var inserted=jdbc.sql("""
                INSERT INTO changes(id,tenant_id,repository_id,provider,provider_change_id,change_type,base_sha,head_sha,ref,forced,title,state,
                    reported_file_count,fetched_file_count,file_set_status,file_set_reason,provider_updated_at,normalized_at)
                VALUES (:id,:tenant,:repo,:provider,:external,:type,:base,:head,:ref,:forced,:title,:state,:reported,:fetched,:fileStatus,:reason,:updated,clock_timestamp())
                ON CONFLICT (tenant_id,repository_id,provider_change_id,head_sha) DO NOTHING RETURNING id
                """).param("id",UUID.randomUUID()).param("tenant",c.tenantId()).param("repo",c.repositoryId()).param("provider",c.provider())
                .param("external",c.providerChangeId()).param("type",c.changeType()).param("base",c.baseSha()).param("head",c.headSha())
                .param("ref",c.ref()).param("forced",c.forced()).param("title",c.title()).param("state",c.state())
                .param("reported",c.reportedFileCount()).param("fetched",c.files().size()).param("fileStatus",c.fileSetStatus())
                .param("reason",c.fileSetReason()).param("updated",timestamp(c.providerUpdatedAt())).query(UUID.class).optional();
        UUID id=inserted.orElseGet(()->jdbc.sql("SELECT id FROM changes WHERE tenant_id=:tenant AND repository_id=:repo AND provider_change_id=:external AND head_sha=:head")
                .param("tenant",c.tenantId()).param("repo",c.repositoryId()).param("external",c.providerChangeId()).param("head",c.headSha()).query(UUID.class).single());
        if(inserted.isPresent()) {
            for(var file:c.files()) jdbc.sql("""
                    INSERT INTO change_files(id,tenant_id,change_id,path,previous_path,status,additions,deletions,changes,blob_after_sha,blob_before_sha,language)
                    VALUES (:id,:tenant,:change,:path,:previous,:status,:additions,:deletions,:changes,:after,:before,:language)
                    """).param("id",UUID.randomUUID()).param("tenant",c.tenantId()).param("change",id).param("path",file.path())
                    .param("previous",file.previousPath()).param("status",file.status()).param("additions",file.additions())
                    .param("deletions",file.deletions()).param("changes",file.changes()).param("after",file.blobAfterSha())
                    .param("before",file.blobBeforeSha()).param("language",file.language()).update();
            emit(c.tenantId(),id,"change.normalized",Map.of("change_id",id,"repository_id",c.repositoryId(),"head_sha",c.headSha(),"file_set_status",c.fileSetStatus()),correlation,causation);
        }
        jdbc.sql("""
                INSERT INTO change_current_revisions(tenant_id,repository_id,provider_change_id,change_id,head_sha,observed_at)
                VALUES (:tenant,:repo,:external,:id,:head,clock_timestamp())
                ON CONFLICT (tenant_id,repository_id,provider_change_id) DO UPDATE
                SET change_id=EXCLUDED.change_id,head_sha=EXCLUDED.head_sha,observed_at=EXCLUDED.observed_at
                """).param("tenant",c.tenantId()).param("repo",c.repositoryId()).param("external",c.providerChangeId())
                .param("id",id).param("head",c.headSha()).update();
        return id;
    }
    @Transactional public void observeCi(UUID tenant,UUID repo,String sha,List<CiObservation> observations,UUID correlation,UUID causation) {
        context.setTenant(tenant);
        for(var c:observations) {
            UUID id=UUID.randomUUID();
            int changed=jdbc.sql("""
                    INSERT INTO ci_observations(id,tenant_id,repository_id,head_sha,provider,source_kind,external_check_id,source_app_id,name,status,conclusion,
                      started_at,completed_at,details_url,observed_at,provider_updated_at)
                    VALUES (:id,:tenant,:repo,:sha,:provider,:kind,:external,:app,:name,:status,:conclusion,:started,:completed,:url,clock_timestamp(),:updated)
                    ON CONFLICT (tenant_id,repository_id,head_sha,source_kind,external_check_id,source_app_id) DO UPDATE
                    SET status=EXCLUDED.status,conclusion=EXCLUDED.conclusion,completed_at=EXCLUDED.completed_at,
                      observed_at=EXCLUDED.observed_at,provider_updated_at=EXCLUDED.provider_updated_at
                    WHERE (ci_observations.provider_updated_at IS NULL OR EXCLUDED.provider_updated_at>=ci_observations.provider_updated_at)
                      AND (ci_observations.status,ci_observations.conclusion) IS DISTINCT FROM (EXCLUDED.status,EXCLUDED.conclusion)
                    """).param("id",id).param("tenant",tenant).param("repo",repo).param("sha",sha).param("provider",c.provider())
                    .param("kind",c.sourceKind()).param("external",c.externalId()).param("app",c.sourceAppId()).param("name",c.name())
                    .param("status",c.status()).param("conclusion",c.conclusion()).param("started",timestamp(c.startedAt()))
                    .param("completed",timestamp(c.completedAt())).param("url",c.detailsUrl()).param("updated",timestamp(c.providerUpdatedAt())).update();
            if(changed>0) emit(tenant,repo,"ci.observation.updated",Map.of("repository_id",repo,"head_sha",sha,"external_check_id",c.externalId()),correlation,causation);
        }
    }
    private void emit(UUID tenant,UUID id,String type,Map<String,Object> payload,UUID correlation,UUID causation) {
        outbox.append(new OutboxCommand(tenant,"CHANGE",id,type,1,payload,correlation,causation,Instant.now()));
    }
    private static Timestamp timestamp(Instant value) {return value==null?null:Timestamp.from(value);}
}
