package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.spi.AuditSink;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous audit sink leveraging Java 25 Virtual Threads.
 * Offloads audit persistence without adding any blocking latency to the pricing pipeline.
 *
 * <p>At most {@value #MAX_IN_FLIGHT} records may be queued at once. Fire-and-forget submission with
 * no ceiling turns an audit-store stall into an unbounded virtual-thread and heap leak, and the
 * failures were previously reported only to stderr.
 */
public class VirtualThreadAuditSink implements AuditSink, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VirtualThreadAuditSink.class.getName());
    static final int MAX_IN_FLIGHT = 10_000;

    private final AuditSink delegate;
    private final ExecutorService virtualExecutor;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    public VirtualThreadAuditSink(AuditSink delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate AuditSink cannot be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void record(PricingResult result) {
        Objects.requireNonNull(result, "PricingResult cannot be null");
        if (!inFlight.tryAcquire()) {
            throw new IllegalStateException(
                "Audit backlog exceeds " + MAX_IN_FLIGHT
                    + " in-flight records; refusing to queue without bound");
        }
        try {
            virtualExecutor.submit(() -> {
                try {
                    delegate.record(result);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR,
                        "Asynchronous audit record failed for calculation " + result.calculationId(), e);
                } finally {
                    inFlight.release();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.release();
            throw e;
        }
    }

    @Override
    public void close() {
        virtualExecutor.shutdown();
        try {
            if (!virtualExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                virtualExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualExecutor.shutdownNow();
        }
    }
}
