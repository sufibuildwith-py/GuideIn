package io.guidein.jobs.api;

public enum FailureCategory {
    TRANSIENT_PROVIDER(true), RATE_LIMIT(true), TIMEOUT(true), INVALID_INPUT(false),
    AUTHENTICATION(false), AUTHORIZATION(false), CONFLICT(false), STALE_SOURCE(false),
    POLICY_ERROR(false), DEPENDENCY_UNAVAILABLE(true), INTERNAL(true);

    private final boolean retryable;
    FailureCategory(boolean retryable) { this.retryable = retryable; }
    public boolean retryable() { return retryable; }
}

