package io.guidein.jobs.api;

import java.time.Instant;
import java.util.UUID;

public record ClaimedJob(UUID id, UUID tenantId, String jobType, String dedupeKey, String payloadJson,
                         int attemptCount, int maxAttempts, UUID leaseToken, Instant leaseUntil,
                         UUID correlationId, UUID causationId) {}

