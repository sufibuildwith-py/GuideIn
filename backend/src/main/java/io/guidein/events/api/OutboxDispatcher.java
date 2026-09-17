package io.guidein.events.api;

import java.util.UUID;

public interface OutboxDispatcher {
    boolean dispatchNext(UUID tenantId, OutboxConsumer consumer);
    boolean dispatchNext(UUID tenantId, String eventType, OutboxConsumer consumer);
}

