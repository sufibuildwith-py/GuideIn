package io.guidein.provenance.api;

import java.time.Instant;
import java.util.UUID;

public interface ProvenanceWriter {
    void append(Observation observation,UUID correlationId,UUID causationId);
    record Observation(UUID tenantId,UUID changeId,String provider,long repositoryExternalId,long installationExternalId,
                       String subjectDigest,String actorExternalId,String actorType,Boolean verified,String reason,
                       Instant verifiedAt,String providerRequestId,boolean transportVerified,String trustClass) { }
}
