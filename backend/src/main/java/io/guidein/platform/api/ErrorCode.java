package io.guidein.platform.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required", false),
    AUTHORIZATION_DENIED(HttpStatus.FORBIDDEN, "Access denied", false),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found", false),
    TENANT_CONTEXT_MISSING(HttpStatus.FORBIDDEN, "Tenant context missing", false),
    TENANT_ACCESS_DENIED(HttpStatus.NOT_FOUND, "Resource not found", false),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed", false),
    CONFLICT(HttpStatus.CONFLICT, "Conflict", false),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency conflict", false),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dependency unavailable", true),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", false);

    private final HttpStatus status;
    private final String title;
    private final boolean retryable;

    ErrorCode(HttpStatus status, String title, boolean retryable) {
        this.status = status;
        this.title = title;
        this.retryable = retryable;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }
    public boolean retryable() { return retryable; }
}

