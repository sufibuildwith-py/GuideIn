package io.guidein.platform.api;

import java.util.UUID;

public record RequestIdentity(UUID requestId, UUID correlationId) {}

