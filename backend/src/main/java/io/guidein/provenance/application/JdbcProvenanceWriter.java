package io.guidein.provenance.application;

import io.guidein.provenance.api.ProvenanceWriter;
import io.guidein.events.api.*;
import io.guidein.platform.api.TenantContext;
import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
final class JdbcProvenanceWriter implements ProvenanceWriter {
    private final JdbcClient jdbc;
    private final TenantContext context;
    private final OutboxWriter outbox;
    JdbcProvenanceWriter(JdbcClient jdbc,TenantContext context,OutboxWriter outbox) {this.jdbc=jdbc;this.context=context;this.outbox=outbox;}
    @Transactional public void append(Observation o,UUID correlation,UUID causation) {
        context.setTenant(o.tenantId());
        int inserted=jdbc.sql("""
                INSERT INTO provenance_records(id,tenant_id,change_id,provider,repository_external_id,installation_external_id,subject_digest,
                  actor_external_id,actor_type,signature_verified,signature_reason,signature_verified_at,provider_request_id,transport_verified,trust_class,observed_at)
                VALUES (:id,:tenant,:change,:provider,:repo,:installation,:sha,:actor,:type,:verified,:reason,:at,:request,:transport,:trust,clock_timestamp())
                ON CONFLICT (tenant_id,change_id,subject_digest) DO NOTHING
                """).param("id",UUID.randomUUID()).param("tenant",o.tenantId()).param("change",o.changeId()).param("provider",o.provider())
                .param("repo",o.repositoryExternalId()).param("installation",o.installationExternalId()).param("sha",o.subjectDigest())
                .param("actor",o.actorExternalId()).param("type",o.actorType()).param("verified",o.verified()).param("reason",o.reason())
                .param("at",o.verifiedAt()==null?null:java.sql.Timestamp.from(o.verifiedAt())).param("request",o.providerRequestId())
                .param("transport",o.transportVerified()).param("trust",o.trustClass()).update();
        if(inserted>0) outbox.append(new OutboxCommand(o.tenantId(),"CHANGE",o.changeId(),"provenance.resolved",1,
                Map.of("change_id",o.changeId(),"subject_digest",o.subjectDigest()),correlation,causation,Instant.now()));
    }
}
