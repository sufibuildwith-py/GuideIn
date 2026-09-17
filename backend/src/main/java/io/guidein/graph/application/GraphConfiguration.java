package io.guidein.graph.application;

import io.guidein.graph.api.GraphBuilds;
import io.guidein.github.api.RepositoryMaterialSource;
import io.guidein.jobs.api.JobQueue;
import io.guidein.events.api.OutboxWriter;
import io.guidein.platform.api.*;
import io.guidein.tenancy.api.RepositoryQuery;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="guidein.graph.enabled", havingValue="true")
public class GraphConfiguration {
    @Bean io.guidein.graph.api.SystemGraph systemGraph(GraphStore store, RepositoryQuery repositories, GraphBuilds builds) {
        return new GraphQueries(store, repositories, builds);
    }
    @Bean GraphTelemetry graphTelemetry(io.micrometer.core.instrument.MeterRegistry registry){return new GraphTelemetry(registry);}
    @Bean GraphEngine graphEngine(CanonicalJson canonical,GraphTelemetry telemetry) { return new GraphEngine(canonical, GraphLimits.defaults()).telemetry(telemetry); }
    @Bean GraphStore graphStore(JdbcClient jdbc, JdbcTemplate template, TenantContext context, PlatformTransactionManager manager,
                                CanonicalJson canonical, JobQueue jobs, OutboxWriter outbox,GraphTelemetry telemetry) {
        return new GraphStore(jdbc, template, context, manager, canonical, jobs, outbox,telemetry);
    }
    @Bean GraphBuilds graphBuilds(GraphStore store, GraphEngine engine, RepositoryMaterialSource source,
                                 JobQueue jobs, RepositoryQuery repositories, CanonicalJson canonical,
                                 @org.springframework.beans.factory.annotation.Value("${guidein.jobs.lease-duration:30s}") java.time.Duration leaseDuration) {
        return new DurableGraphBuilds(store, engine, source, jobs, repositories, canonical, leaseDuration);
    }
}
