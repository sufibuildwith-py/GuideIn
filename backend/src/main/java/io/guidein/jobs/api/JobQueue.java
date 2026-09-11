package io.guidein.jobs.api;

import java.util.Optional;
import java.util.UUID;

public interface JobQueue {
    UUID enqueue(JobCommand command);
    Optional<ClaimedJob> claimNext(UUID tenantId);
    boolean complete(UUID tenantId, UUID jobId, UUID leaseToken);
    JobStatus fail(UUID tenantId, UUID jobId, UUID leaseToken, FailureCategory category, String errorCode);
}

