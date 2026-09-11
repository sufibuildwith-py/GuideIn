package io.guidein.events.api;

@FunctionalInterface
public interface OutboxConsumer {
    void accept(OutboxEvent event) throws Exception;
}

