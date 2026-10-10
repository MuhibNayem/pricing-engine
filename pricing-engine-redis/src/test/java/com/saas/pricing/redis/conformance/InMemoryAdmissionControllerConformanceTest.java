package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.impl.TokenBucketAdmissionController;

import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

/**
 * Runs the shared contract against the in-memory limiter.
 *
 * <p>This is the reference: if the distributed limiter ever permits something this one rejects,
 * the limiter is more permissive in production than the tests describe — which is the failure mode
 * nobody notices until a tenant's quota is quietly unenforced.</p>
 */
@DisplayName("AdmissionController conformance — in-memory")
class InMemoryAdmissionControllerConformanceTest extends AdmissionControllerConformance {

    @Override
    protected AdmissionController controller(ManualClock clock) {
        // Burst 5, refill 5/second, concurrency ceiling far above the burst so these tests measure
        // rate behaviour only. The ceiling has its own dedicated suite in core.
        return controller(clock, 1_000_000);
    }

    @Override
    protected AdmissionController controller(ManualClock clock, int maxConcurrency) {
        return new TokenBucketAdmissionController(5, Duration.ofSeconds(1), 5, maxConcurrency,
            1_000, Duration.ofMillis(50), clock::nanos);
    }
}