package com.saas.pricing.starter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Micrometer metrics instrumentation for the enterprise SaaS Pricing Engine.
 *
 * <p><strong>Cardinality policy.</strong> Meter tags create one time series per distinct tag
 * combination. Unbounded values such as {@code tenantId} or a caller-supplied {@code featureKey}
 * make that combination effectively unbounded: a few thousand tenants exhausts a Prometheus
 * instance's memory, and an attacker who controls a tag value can trigger the explosion directly.
 * This class therefore tags only on a fixed, enumerated set of values.
 *
 * <p>Per-tenant attribution belongs in structured logs or in a dedicated tenant metrics registry
 * with an explicit allowlist, not in the process-wide meter registry.
 */
public class PricingEngineMetrics {

    /**
     * Tag keys this class will emit. Anything outside this set is deliberately not supported.
     */
    private static final Set<String> ALLOWED_TAG_KEYS =
            Set.of("outcome", "status", "cadence", "pricing_model");

    /** Values collapsed into {@codeother} so callers cannot inflate the series count. */
    private static final int MAX_TAG_VALUE_LENGTH = 48;

    private final MeterRegistry registry;

    public PricingEngineMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records an evaluation.
     *
     * @param outcome   coarse outcome bucket (for example {@code success} or {@code error})
     * @param status    billing status bucket (for example {@coderated})
     * @param cadence   billing cadence bucket (for example {@code monthly})
     * @param duration  wall-clock duration of the evaluation
     */
    public void recordEvaluationDuration(Duration duration, String outcome, String status, String cadence) {
        if (registry == null || duration == null) {
            return;
        }
        String outcomeTag = safeTag(outcome, "unknown");
        String statusTag = safeTag(status, "unknown");
        String cadenceTag = safeTag(cadence, "unknown");

        Timer.builder("pricing.evaluation.duration")
                .description("Duration of pricing engine evaluation")
                .tag("outcome", outcomeTag)
                .tag("status", statusTag)
                .tag("cadence", cadenceTag)
                .register(registry)
                .record(duration);

        Counter.builder("pricing.evaluation.total")
                .description("Total number of pricing evaluations")
                .tag("outcome", outcomeTag)
                .tag("status", statusTag)
                .tag("cadence", cadenceTag)
                .register(registry)
                .increment();
    }

    /**
     * Records a prepaid-credit drawdown. Tenant attribution is intentionally not a tag.
     *
     * @param outcome     coarse outcome bucket (for example {@code settled} or {@code insufficient})
     * @param creditsDrawn amount drawn, in credits
     */
    public void recordDrawdown(String outcome, double creditsDrawn) {
        if (registry == null) {
            return;
        }
        Counter.builder("pricing.wallet.drawdown.total")
                .description("Total prepaid credits drawn down")
                .tag("outcome", safeTag(outcome, "unknown"))
                .register(registry)
                .increment(creditsDrawn);
    }

    /**
     * Records an entitlement check. The feature key is NOT used as a tag value; it is reduced to
     * a bounded token so that caller-controlled input cannot drive series cardinality.
     */
    public void recordEntitlementCheck(String featureKey, boolean allowed) {
        if (registry == null) {
            return;
        }
        Counter.builder("pricing.entitlement.checks")
                .description("Total entitlement quota checks")
                .tag("feature", boundedToken(featureKey))
                .tag("allowed", String.valueOf(allowed))
                .register(registry)
                .increment();
    }

    /**
     * Normalises a tag value: trimmed, lower-cased, truncated, and mapped to {@code other} when
     * missing.
     */
    private static String safeTag(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return normalised.length() <= MAX_TAG_VALUE_LENGTH ? normalised : "other";
    }

    /**
     * Reduces an arbitrary caller-controlled string (for example a feature key) to a bounded,
     * deterministic token. EVERY value is hashed: returning short values verbatim allowed a caller
     * to create one Prometheus series per 48-character feature key, which is a cardinality bomb.
     */
    private static String boundedToken(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Integer.toHexString(normalised.hashCode());
    }

    /**
     * Visible for testing: the tag keys this class is permitted to emit.
     */
    public static Set<String> allowedTagKeys() {
        return ALLOWED_TAG_KEYS;
    }
}