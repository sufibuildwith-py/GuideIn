package io.guidein.jobs.api;

import java.util.Optional;
import java.util.UUID;

public interface JobQueue {
    UUID enqueue(JobCommand command);
    Optional<ClaimedJob> claimNext(UUID tenantId);
    boolean complete(UUID tenantId, UUID jobId, UUID leaseToken);
    boolean renew(UUID tenantId, UUID jobId, UUID leaseToken);
    Optional<JobStatus> status(UUID tenantId, UUID jobId);
    JobStatus fail(UUID tenantId, UUID jobId, UUID leaseToken, FailureCategory category, String errorCode);
    Optional<ClaimedJob> claimNext(UUID tenantId, String jobType);
    JobStatus failNotBefore(UUID tenantId, UUID jobId, UUID leaseToken, FailureCategory category,
                            String errorCode, java.time.Instant notBefore);
}
