package io.guidein.observability.api;

public interface KernelMetrics {
    void authorizationDenied(boolean crossTenant);
    void auditAppended();
    void auditIntegrityFailure();
    void outboxAppended();
    void outboxPublished();
    void outboxDispatchFailure();
    void jobEnqueued();
    void jobTerminal();
    void jobClaimed();
    void jobRetried();
    void jobDead();
}
