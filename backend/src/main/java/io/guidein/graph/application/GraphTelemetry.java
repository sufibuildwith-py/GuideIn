package io.guidein.graph.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.*;
import java.util.concurrent.TimeUnit;

/** Fixed names and bounded extractor labels only; durable-result counters advance after commit. */
public final class GraphTelemetry {
    private final MeterRegistry registry;
    public GraphTelemetry(MeterRegistry registry){this.registry=registry;}
    public static GraphTelemetry disabled(){return new GraphTelemetry(null);}
    void published(int nodes,int edges,int gaps,long nanos){afterCommit(()->{
        count("graph.build",1);count("graph.nodes",nodes);count("graph.edges",edges);count("graph.gap",gaps);duration("graph.persistence.duration",nanos);
    });}
    void failed(){afterCommit(()->count("graph.build.failure",1));}
    void buildDuration(long nanos){duration("graph.build.duration",nanos);}
    void reused(){afterCommit(()->count("graph.snapshot.reuse",1));}
    void extracted(String extractor,long nanos,boolean failed){if(registry!=null){registry.timer("graph.extractor.duration","extractor",extractor).record(nanos,TimeUnit.NANOSECONDS);if(failed)registry.counter("graph.extractor.failure","extractor",extractor).increment();}}
    void traversed(long nanos,boolean truncated){duration("graph.traversal.duration",nanos);if(truncated)count("graph.traversal.budget.exceeded",1);}
    private void count(String name,int amount){if(registry!=null)registry.counter(name).increment(amount);}
    private void duration(String name,long nanos){if(registry!=null)registry.timer(name).record(nanos,TimeUnit.NANOSECONDS);}
    private void afterCommit(Runnable update){
        if(TransactionSynchronizationManager.isSynchronizationActive()&&TransactionSynchronizationManager.isActualTransactionActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){update.run();}});
        else update.run();
    }
}
