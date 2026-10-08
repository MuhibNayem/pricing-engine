package com.saas.pricing.starter.streaming;

import com.saas.pricing.metering.engine.UsageMeteringEngine;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.stream.MeterEventPublisher;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring-aware MeterEventPublisher that ingests events via UsageMeteringEngine
 * and broadcasts MeterIngestedApplicationEvent onto the Spring ApplicationContext on virtual threads.
 */
public class SpringMeterEventPublisher implements MeterEventPublisher, AutoCloseable {

    private final UsageMeteringEngine meteringEngine;
    private final ApplicationEventPublisher eventPublisher;
    private final ExecutorService executor;

    public SpringMeterEventPublisher(UsageMeteringEngine meteringEngine, ApplicationEventPublisher eventPublisher) {
        this(meteringEngine, eventPublisher, Executors.newVirtualThreadPerTaskExecutor());
    }

    public SpringMeterEventPublisher(
        UsageMeteringEngine meteringEngine,
        ApplicationEventPublisher eventPublisher,
        ExecutorService executor
    ) {
        this.meteringEngine = Objects.requireNonNull(meteringEngine, "meteringEngine cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    @Override
    public CompletableFuture<IngestionResult> publish(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            IngestionResult result = meteringEngine.ingest(event);
            eventPublisher.publishEvent(new MeterIngestedApplicationEvent(this, event, result));
            return result;
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
