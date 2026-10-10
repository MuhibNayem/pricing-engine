package com.saas.pricing.redis.conformance;

import com.saas.pricing.metering.spi.RatingClaimStore;
import com.saas.pricing.metering.spi.impl.InMemoryRatingClaimStore;

import org.junit.jupiter.api.DisplayName;

import java.time.Clock;
import java.time.Duration;

/** Runs the shared contract against the in-memory reference implementation. */
@DisplayName("RatingClaimStore conformance — in-memory")
class InMemoryRatingClaimStoreConformanceTest extends RatingClaimStoreConformance {

    @Override
    protected RatingClaimStore store() {
        return new InMemoryRatingClaimStore(Clock.systemUTC(), Duration.ofDays(90), 1_000);
    }
}