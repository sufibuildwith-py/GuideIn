package io.guidein.change.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChangeWriter {
    UUID normalize(ChangeCandidate candidate,UUID correlationId,UUID causationId);
    void observeCi(UUID tenantId,UUID repositoryId,String headSha,List<CiObservation> observations,UUID correlationId,UUID causationId);
    record CiObservation(String provider,String sourceKind,String externalId,String sourceAppId,String name,
                         String status,String conclusion,Instant startedAt,Instant completedAt,String detailsUrl,
                         Instant providerUpdatedAt) { }
}
