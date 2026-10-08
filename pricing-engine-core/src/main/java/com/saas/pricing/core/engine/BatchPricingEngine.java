package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * High-throughput parallel pricing evaluation engine using Java 25 Virtual Threads.
 * Optimized for rating millions of usage events concurrently with zero OS thread starvation.
 */
public final class BatchPricingEngine implements AutoCloseable {

    private final PricingEngine pricingEngine;
    private final ExecutorService virtualExecutor;

    public BatchPricingEngine(PricingEngine pricingEngine) {
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine cannot be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public List<PricingResult> evaluateBatch(List<PricingRequest> requests) {
        Objects.requireNonNull(requests, "requests cannot be null");
        if (requests.isEmpty()) {
            return List.of();
        }

        List<Future<PricingResult>> futures = requests.stream()
            .map(req -> virtualExecutor.submit(() -> pricingEngine.evaluate(req)))
            .toList();

        return futures.stream()
            .map(f -> {
                try {
                    return f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Batch pricing evaluation interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException("Batch pricing evaluation error", cause);
                }
            })
            .toList();
    }

    @Override
    public void close() {
        virtualExecutor.close();
    }
}
