package io.guidein.observability.application;

import io.guidein.observability.api.KernelMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
final class MicrometerKernelMetrics implements KernelMetrics {
    private final Counter authorizationDenied;
    private final Counter crossTenantDenied;
    private final Counter auditAppend;
    private final Counter auditIntegrityFailure;
    private final Counter outboxDispatchFailure;
    private final Counter jobClaim;
    private final Counter jobRetry;
    private final Counter jobDead;
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong jobQueueDepth = new AtomicLong();

    MicrometerKernelMetrics(MeterRegistry registry) {
        authorizationDenied = registry.counter("guidein.authorization.denied");
        crossTenantDenied = registry.counter("guidein.authorization.cross_tenant_denied");
        auditAppend = registry.counter("guidein.audit.append");
        auditIntegrityFailure = registry.counter("guidein.audit.integrity_failure");
        outboxDispatchFailure = registry.counter("guidein.outbox.dispatch_failure");
        jobClaim = registry.counter("guidein.job.claim");
        jobRetry = registry.counter("guidein.job.retry");
        jobDead = registry.counter("guidein.job.dead");
        registry.gauge("guidein.outbox.pending", outboxPending);
        registry.gauge("guidein.job.queue.depth", jobQueueDepth);
    }

    public void authorizationDenied(boolean crossTenant) {
        authorizationDenied.increment();
        if (crossTenant) crossTenantDenied.increment();
    }
    public void auditAppended() { afterCommit(auditAppend::increment); }
    public void auditIntegrityFailure() { auditIntegrityFailure.increment(); }
    public void outboxAppended() { afterCommit(outboxPending::incrementAndGet); }
    public void outboxPublished() { afterCommit(() -> outboxPending.updateAndGet(value -> Math.max(0, value - 1))); }
    public void outboxDispatchFailure() { outboxDispatchFailure.increment(); }
    public void jobEnqueued() { afterCommit(jobQueueDepth::incrementAndGet); }
    public void jobTerminal() { afterCommit(() -> jobQueueDepth.updateAndGet(value -> Math.max(0, value - 1))); }
    public void jobClaimed() { afterCommit(jobClaim::increment); }
    public void jobRetried() { afterCommit(jobRetry::increment); }
    public void jobDead() { afterCommit(jobDead::increment); }

    private void afterCommit(Runnable update) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { update.run(); }
                    });
        } else { update.run(); }
    }
}
