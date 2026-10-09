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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Enterprise event dispatcher bridging stream consumers (Kafka/RabbitMQ/Spring) with
 * the UsageMeteringEngine, running asynchronously on Java 25 virtual threads.
 *
 * <p>Failures are observable: an ingest failure on the fire-and-forget {@link #consume} path and a
 * listener that throws are both logged and counted instead of vanishing. A listener error is still
 * isolated from the other listeners, but it is no longer silent - a lost billing trigger used to
 * disappear without a trace.
 */
public class DefaultMeterEventDispatcher implements MeterEventConsumer, MeterEventPublisher, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DefaultMeterEventDispatcher.class.getName());

    private final UsageMeteringEngine meteringEngine;
    private final List<MeterEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor;
    private final LongAdder ingestionFailures = new LongAdder();
    private final LongAdder listenerFailures = new LongAdder();

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
        // The future used to be discarded, so an ingest or parse failure disappeared entirely.
        publish(event).whenComplete((result, error) -> {
            if (error != null) {
                ingestionFailures.increment();
                LOG.log(System.Logger.Level.ERROR,
                    "Meter event ingestion failed for event " + event.eventId(), error);
            }
        });
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
            } catch (RuntimeException e) {
                listenerFailures.increment();
                LOG.log(System.Logger.Level.ERROR,
                    "Meter listener " + listener.getClass().getName()
                        + " failed for event " + event.eventId(), e);
            }
        }
    }

    /** Visible for monitoring: ingest failures observed on the fire-and-forget path. */
    public long ingestionFailureCount() {
        return ingestionFailures.sum();
    }

    /** Visible for monitoring: listener exceptions observed. */
    public long listenerFailureCount() {
        return listenerFailures.sum();
    }

    @Override
    public void close() {
        // Drain rather than dropping queued work: shutdown() left in-flight ingestions to race the
        // caller's next assertion.
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
