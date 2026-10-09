package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Admission;
import com.saas.pricing.core.spi.AdmissionController.Shedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two things that must never be wrong in a shedding controller: that it never sheds
 * capacity it promised, and that a shed client is told <em>which</em> limit it hit.
 */
@DisplayName("TokenBucketAdmissionController")
class TokenBucketAdmissionControllerTest {

    private static final TenantId ACME = new TenantId("acme");
    private static final TenantId GLOBEX = new TenantId("globex");

    /** Manually advanced clock, so refill behaviour is deterministic rather than timing-dependent. */
    private static final class TestClock {
        private final AtomicLong nanos = new AtomicLong();

        long now() {
            return nanos.get();
        }

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }
    }

    private static TokenBucketAdmissionController controller(TestClock clock, long permits, Duration period,
                                                              long burst, int concurrency, int maxTenants) {
        return new TokenBucketAdmissionController(permits, period, burst, concurrency, maxTenants,
            Duration.ofMillis(50), clock::now);
    }

    @Nested
    @DisplayName("rate limiting")
    class RateLimiting {

        @Test
        @DisplayName("admits exactly the burst, then sheds with RATE_LIMITED")
        void burstThenRateLimited() {
            var clock = new TestClock();
            var controller = controller(clock, 10, Duration.ofSeconds(1), 10, 100, 1_000);

            for (int i = 0; i < 10; i++) {
                assertThat(controller.admit(ACME).admitted()).as("burst call %d", i).isTrue();
            }

            Admission shed = controller.admit(ACME);
            assertThat(shed.admitted()).isFalse();
            assertThat(shed.rateLimited()).isTrue();
            assertThat(shed.overloaded()).isFalse();
            assertThat(shed.retryAfter()).isPositive();
        }

        @Test
        @DisplayName("retryAfter is the real time to one token, so waiting it lets exactly one through")
        void retryAfterIsHonest() {
            var clock = new TestClock();
            var controller = controller(clock, 10, Duration.ofSeconds(1), 10, 100, 1_000);

            for (int i = 0; i < 10; i++) {
                controller.admit(ACME);
            }
            Admission shed = controller.admit(ACME);
            assertThat(shed.admitted()).isFalse();

            // One token accrues every 100ms at 10/sec.
            clock.advance(Duration.ofMillis(99));
            assertThat(controller.admit(ACME).admitted()).as("99ms is not yet enough").isFalse();

            clock.advance(Duration.ofMillis(1));
            assertThat(controller.admit(ACME).admitted()).as("100ms accrues exactly one token").isTrue();
            assertThat(controller.admit(ACME).admitted()).as("and only one").isFalse();
        }

        @Test
        @DisplayName("rate is per tenant: one noisy tenant cannot throttle another")
        void rateIsPerTenant() {
            var clock = new TestClock();
            var controller = controller(clock, 10, Duration.ofSeconds(1), 10, 100, 1_000);

            for (int i = 0; i < 10; i++) {
                controller.admit(ACME);
            }
            assertThat(controller.admit(ACME).admitted()).isFalse();

            assertThat(controller.admit(GLOBEX).admitted())
                .as("a different tenant has its own untouched bucket")
                .isTrue();
        }

        @Test
        @DisplayName("concurrent callers consume exactly the burst and never exceed it")
        void concurrentCallersNeverExceedBurst() throws Exception {
            var clock = new TestClock();
            int burst = 500;
            var controller = controller(clock, burst, Duration.ofHours(1), burst, 10_000, 1_000);

            int threads = 32;
            AtomicInteger admitted = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < 100; i++) {
                            Admission admission = controller.admit(ACME);
                            if (admission.admitted()) {
                                admitted.incrementAndGet();
                                admission.lease().close();
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(admitted.get())
                .as("3,200 attempts against a burst of 500 with no refill must admit exactly 500")
                .isEqualTo(burst);
        }

        @Test
        @DisplayName("idle tenants are reclaimed, but a throttled tenant keeps its drained bucket")
        void idleTenantsAreReclaimed() {
            var clock = new TestClock();
            int maxTenants = 50;
            var controller = controller(clock, 1, Duration.ofHours(1), 1, 1_000, maxTenants);

            for (int i = 0; i < 200; i++) {
                controller.admit(new TenantId("idle-" + i));   // each consumes its only token
            }
            assertThat(controller.trackedTenants())
                .as("drained buckets are live state and must be kept")
                .isGreaterThan(maxTenants);

            // Time passes; every bucket refills to full, so none is worth retaining.
            clock.advance(Duration.ofHours(2));
            controller.admit(new TenantId("trigger"));
            assertThat(controller.trackedTenants())
                .as("full buckets carry no state worth keeping")
                .isLessThanOrEqualTo(maxTenants + 1);
        }
    }

    @Nested
    @DisplayName("concurrency ceiling")
    class Concurrency {

        @Test
        @DisplayName("sheds with OVERLOADED once in-flight work reaches the cap")
        void shedsOverloadedAtTheCap() {
            var clock = new TestClock();
            var controller = controller(clock, 1_000, Duration.ofHours(1), 1_000, 2, 1_000);

            Admission first = controller.admit(ACME);
            Admission second = controller.admit(ACME);
            assertThat(first.admitted()).isTrue();
            assertThat(second.admitted()).isTrue();

            Admission third = controller.admit(ACME);
            assertThat(third.admitted()).isFalse();
            assertThat(third.overloaded()).isTrue();
            assertThat(third.rateLimited())
                .as("a saturated engine is not that tenant's quota fault - this must map to 503, not 429")
                .isFalse();

            first.lease().close();
            assertThat(controller.admit(ACME).admitted()).as("closing a lease frees its slot").isTrue();
        }

        @Test
        @DisplayName("the ceiling is global, not per tenant")
        void ceilingIsGlobalNotPerTenant() {
            var clock = new TestClock();
            var controller = controller(clock, 1_000, Duration.ofHours(1), 1_000, 1, 1_000);

            Admission held = controller.admit(ACME);
            assertThat(held.admitted()).isTrue();

            assertThat(controller.admit(GLOBEX).admitted())
                .as("one node's capacity does not multiply by tenant count")
                .isFalse();
        }

        @Test
        @DisplayName("a tenant shed for concurrency gets its token back")
        void concurrencyShedRefundsTheToken() {
            var clock = new TestClock();
            // Burst of 2 with no meaningful refill, concurrency ceiling of 1.
            var controller = controller(clock, 1, Duration.ofHours(1), 2, 1, 1_000);

            Admission first = controller.admit(ACME);
            assertThat(first.admitted()).isTrue();          // burst 2 -> 1

            Admission blocked = controller.admit(ACME);
            assertThat(blocked.overloaded()).isTrue();      // token taken then refunded, back to 1

            first.lease().close();

            // The refund is the whole point: without it the bucket would sit at 0 and this would be
            // RATE_LIMITED, penalising a tenant for an overload that was not its fault.
            assertThat(controller.admit(ACME).admitted())
                .as("the refunded token is still available")
                .isTrue();                                  // bucket 1 -> 0
            assertThat(controller.admit(ACME).rateLimited())
                .as("and the burst really was only 2, so the bucket is now genuinely empty")
                .isTrue();
        }

        @Test
        @DisplayName("closing a lease twice is harmless")
        void closingTwiceIsHarmless() {
            var clock = new TestClock();
            var controller = controller(clock, 1_000, Duration.ofHours(1), 1_000, 2, 1_000);

            Admission a = controller.admit(ACME);
            Admission b = controller.admit(ACME);
            assertThat(a.admitted()).isTrue();
            assertThat(b.admitted()).isTrue();
            assertThat(controller.admit(ACME).overloaded()).as("cap of 2 reached").isTrue();

            a.lease().close();
            a.lease().close();

            assertThat(controller.inFlight())
                .as("the second close must be a no-op, not a second release")
                .isEqualTo(1);

            assertThat(controller.admit(ACME).admitted())
                .as("exactly one slot was freed")
                .isTrue();
            assertThat(controller.admit(ACME).overloaded())
                .as("a second spurious release would have let this through too")
                .isTrue();
            assertThat(controller.admit(ACME).overloaded()).isTrue();

            b.lease().close();
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("UNBOUNDED admits everything")
        void unboundedAdmitsEverything() {
            Admission admission = AdmissionController.UNBOUNDED.admit(ACME);
            assertThat(admission.admitted()).isTrue();
            admission.lease().close();
            assertThat(AdmissionController.UNBOUNDED.admit(ACME).admitted()).isTrue();
        }

        @Test
        @DisplayName("a shed admission carries a usable Retry-After and an admitted one does not")
        void retryAfterOnlyOnShed() {
            var clock = new TestClock();
            var controller = controller(clock, 1, Duration.ofSeconds(1), 1, 100, 1_000);

            Admission granted = controller.admit(ACME);
            assertThat(granted.admitted()).isTrue();
            assertThat(granted.retryAfter()).isNull();

            Admission shed = controller.admit(ACME);
            assertThat(shed.admitted()).isFalse();
            assertThat(shed.retryAfter()).isNotNull();
            assertThat(shed.retryAfter().isNegative()).isFalse();
            assertThat(shed.reason()).isEqualTo(Shedding.RATE_LIMITED);
        }
    }
}