package io.guidein.events.api;

import java.util.UUID;

public interface OutboxWriter {
    UUID append(OutboxCommand command);
}

