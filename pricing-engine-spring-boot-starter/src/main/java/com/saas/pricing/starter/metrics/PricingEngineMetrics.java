package com.saas.pricing.starter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Micrometer metrics instrumentation for the enterprise SaaS Pricing Engine.
 */
public class PricingEngineMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> evaluationCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> drawdownCounters = new ConcurrentHashMap<>();

    public PricingEngineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordEvaluationDuration(Duration duration, String tenantId, String planCode, boolean success) {
        if (registry != null) {
            Timer.builder("pricing.evaluation.duration")
                .description("Duration of pricing engine evaluation")
                .tag("tenant", tenantId)
                .tag("plan", planCode)
                .tag("success", String.valueOf(success))
                .register(registry)
                .record(duration);

            String key = tenantId + ":" + planCode + ":" + success;
            evaluationCounters.computeIfAbsent(key, k ->
                Counter.builder("pricing.evaluation.total")
                    .description("Total number of pricing evaluations")
                    .tag("tenant", tenantId)
                    .tag("plan", planCode)
                    .tag("success", String.valueOf(success))
                    .register(registry)
            ).increment();
        }
    }

    public void recordDrawdown(String tenantId, double creditsAmount) {
        if (registry != null) {
            drawdownCounters.computeIfAbsent(tenantId, k ->
                Counter.builder("pricing.wallet.drawdown.total")
                    .description("Total prepaid credits drawn down")
                    .tag("tenant", tenantId)
                    .register(registry)
            ).increment(creditsAmount);
        }
    }

    public void recordEntitlementCheck(String featureKey, boolean allowed) {
        if (registry != null) {
            Counter.builder("pricing.entitlement.checks")
                .description("Total entitlement quota checks")
                .tag("feature", featureKey)
                .tag("allowed", String.valueOf(allowed))
                .register(registry)
                .increment();
        }
    }
}
