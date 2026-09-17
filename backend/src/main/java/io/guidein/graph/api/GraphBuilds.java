package io.guidein.graph.api;

import io.guidein.identity.api.AuthenticatedSubject;
import java.util.UUID;

public interface GraphBuilds {
    SystemGraph.Snapshot request(AuthenticatedSubject subject, UUID tenant, UUID repository, String sha, UUID correlation);
    SystemGraph.Snapshot requestNormalized(UUID tenant, UUID repository, String sha, UUID correlation, UUID causation);
    boolean processNext(UUID tenant);
}
