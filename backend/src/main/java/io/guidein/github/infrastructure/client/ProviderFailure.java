package io.guidein.github.infrastructure.client;

import io.guidein.jobs.api.FailureCategory;
import java.time.Instant;

/** Contains stable classification only, never a provider body, URI, or secret. */
public final class ProviderFailure extends RuntimeException {
    private final FailureCategory category;
    private final Instant retryAt;
    public ProviderFailure(FailureCategory category, Instant retryAt) {
        super("GITHUB_" + category.name());
        this.category = category;
        this.retryAt = retryAt;
    }
    public FailureCategory category() { return category; }
    public Instant retryAt() { return retryAt; }
}
