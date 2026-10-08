package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.spi.AuditSink;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous audit sink leveraging Java 25 Virtual Threads.
 * Offloads audit persistence without adding any blocking latency to the pricing pipeline.
 */
public class VirtualThreadAuditSink implements AuditSink, AutoCloseable {

    private final AuditSink delegate;
    private final ExecutorService virtualExecutor;

    public VirtualThreadAuditSink(AuditSink delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate AuditSink cannot be null");
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void record(PricingResult result) {
        Objects.requireNonNull(result, "PricingResult cannot be null");
        virtualExecutor.submit(() -> {
            try {
                delegate.record(result);
            } catch (Exception e) {
                // Log or handle audit recording exception without failing the caller
                System.err.println("Failed to record audit asynchronously: " + e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        virtualExecutor.close();
    }
}
