package io.guidein.graph.application;

import io.guidein.events.api.OutboxDispatcher;
import io.guidein.github.api.RepositoryMaterialSource;
import io.guidein.graph.api.GraphBuilds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;

@Component
@ConditionalOnProperty(name={"guidein.graph.enabled","guidein.graph.worker-enabled"},havingValue="true")
public final class GraphWorker {
    private final RepositoryMaterialSource source; private final GraphBuilds builds; private final OutboxDispatcher outbox;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public GraphWorker(RepositoryMaterialSource source,GraphBuilds builds,OutboxDispatcher outbox){this.source=source;this.builds=builds;this.outbox=outbox;}
    @Scheduled(fixedDelayString="${guidein.graph.poll-delay-ms:1000}")
    public void tick(){
        for(UUID tenant:source.routedTenants())try {
            dispatchChange(tenant);
            builds.processNext(tenant);
        }catch(RuntimeException failed){ /* Existing durable outbox/job leases retain unacknowledged work. */ }
    }
    public boolean dispatchChange(UUID tenant) {
        return outbox.dispatchNext(tenant,"change.normalized",event->{
            var payload=mapper.readTree(event.payloadJson());
            builds.requestNormalized(tenant,UUID.fromString(payload.path("repository_id").asText()),payload.path("head_sha").asText(),event.correlationId(),event.id());
        });
    }
}
