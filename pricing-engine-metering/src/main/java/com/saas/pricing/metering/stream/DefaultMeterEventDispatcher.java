package com.saas.pricing.metering.stream;

import com.saas.pricing.metering.engine.UsageMeteringEngine;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enterprise event dispatcher bridging stream consumers (Kafka/RabbitMQ/Spring) with
 * the UsageMeteringEngine, running asynchronously on Java 25 virtual threads.
 */
public class DefaultMeterEventDispatcher implements MeterEventConsumer, MeterEventPublisher, AutoCloseable {

    private final UsageMeteringEngine meteringEngine;
    private final List<MeterEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor;

    public DefaultMeterEventDispatcher(UsageMeteringEngine meteringEngine) {
        this(meteringEngine, Executors.newVirtualThreadPerTaskExecutor());
    }

    public DefaultMeterEventDispatcher(UsageMeteringEngine meteringEngine, ExecutorService executor) {
        this.meteringEngine = Objects.requireNonNull(meteringEngine, "meteringEngine cannot be null");
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
    }

    public void addListener(MeterEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(MeterEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public void consume(MeterEvent event) {
        publish(event);
    }

    @Override
    public CompletableFuture<IngestionResult> publish(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        return CompletableFuture.supplyAsync(() -> {
            IngestionResult result = meteringEngine.ingest(event);
            notifyListeners(event, result);
            return result;
        }, executor);
    }

    private void notifyListeners(MeterEvent event, IngestionResult result) {
        for (MeterEventListener listener : listeners) {
            try {
                listener.onEventIngested(event, result);
            } catch (Exception ignored) {
                // Prevent one failing listener from blocking other listeners
            }
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
