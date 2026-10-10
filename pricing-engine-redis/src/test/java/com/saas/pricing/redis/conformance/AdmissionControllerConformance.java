package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behavioural contract every {@link AdmissionController} must satisfy.
 *
 * <p>The reason this exists is drift between the local and distributed limiters. They are
 * interchangeable only if they admit the same requests for the same instants, and the tempting
 * shortcut — Redis's own Java guide refills on discrete intervals — makes them differ at the margin
 * without anyone noticing. A limiter that is subtly more permissive in production than in the unit
 * test is not a limiter anyone can rely on.</p>
 *
 * <p>The clock is injected so refill is asserted at exact instants rather than inferred from sleeps.
 * That is a test seam, and a seam is only worth having if what it hides is still tested elsewhere:
 * the Redis implementation's <em>production</em> path refuses to take a clock at all and reads
 * {@code TIME} from the server instead, so
 * {@link RedisAdmissionControllerConformanceTest} covers that constructor directly.</p>
 */
@DisplayName("AdmissionController conformance")
abstract class AdmissionControllerConformance {

    protected static final TenantId ACME = new TenantId("acme");
    protected static final TenantId GLOBEX = new TenantId("globex");

    /** Burst of 5, refilling at 5 per second, with a concurrency ceiling high enough not to interfere. */
    protected abstract AdmissionController controller(ManualClock clock);

    /** As {@link #controller(ManualClock)}, but with a ceiling low enough to trigger {@code OVERLOADED}. */
    protected abstract AdmissionController controller(ManualClock clock, int maxConcurrency);

    /**
     * A clock the test drives, so refill behaviour is deterministic rather than timing-dependent.
     *
     * <p>Both a {@link Clock} and a {@code nanos()} accessor: the in-memory limiter is a nanosecond
     * monotonic consumer and the Redis one is a millisecond epoch consumer, and neither should be
     * asked to pretend to be the other.</p>
     */
    static final class ManualClock extends Clock {

        private long millis;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }

        long nanos() {
            return millis * 1_000_000L;
        }

        void advance(Duration by) {
            millis += by.toMillis();
        }
    }

    @Test
    @DisplayName("admits exactly the burst, then sheds as RATE_LIMITED")
    void burstThenRateLimited() {
        ManualClock clock = new ManualClock();
        AdmissionController controller = controller(clock);

        for (int i = 0; i < 5; i++) {
            Admission admission = controller.admit(ACME);
            assertThat(admission.admitted()).as("burst call %d", i).isTrue();
            admission.lease().close();
        }

        Admission shed = controller.admit(ACME);
        assertThat(shed.admitted()).isFalse();
        assertThat(shed.rateLimited()).isTrue();
        assertThat(shed.overloaded()).isFalse();
        assertThat(shed.retryAfter()).isPositive();
    }

    @Test
    @DisplayName("refill is continuous: 200ms buys exactly one token at 5/s")
    void refillIsContinuous() {
        ManualClock clock = new ManualClock();
        AdmissionController controller = controller(clock);

        for (int i = 0; i < 5; i++) {
            controller.admit(ACME).lease().close();
        }
        assertThat(controller.admit(ACME).admitted()).isFalse();

        clock.advance(Duration.ofMillis(199));
        assertThat(controller.admit(ACME).admitted())
            .as("199ms is not yet one token at 5/s")
            .isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(controller.admit(ACME).admitted())
            .as("continuous refill, not discrete: 200ms is exactly one token, not a whole interval's worth")
            .isTrue();
        assertThat(controller.admit(ACME).admitted()).as("and only one").isFalse();
    }

    @Test
    @DisplayName("refill is capped at the burst: idling does not bank unlimited credit")
    void refillIsCappedAtBurst() {
        ManualClock clock = new ManualClock();
        AdmissionController controller = controller(clock);

        for (int i = 0; i < 5; i++) {
            controller.admit(ACME).lease().close();
        }
        clock.advance(Duration.ofHours(1));

        int admitted = 0;
        for (int i = 0; i < 50; i++) {
            Admission admission = controller.admit(ACME);
            if (admission.admitted()) {
                admitted++;
                admission.lease().close();
            }
        }
        assertThat(admitted)
            .as("an hour of idling must not buy more than the configured burst")
            .isEqualTo(5);
    }

    @Test
    @DisplayName("quota is per tenant: one noisy tenant cannot throttle another")
    void quotaIsPerTenant() {
        ManualClock clock = new ManualClock();
        AdmissionController controller = controller(clock);

        for (int i = 0; i < 5; i++) {
            controller.admit(ACME).lease().close();
        }
        assertThat(controller.admit(ACME).admitted()).isFalse();

        Admission other = controller.admit(GLOBEX);
        assertThat(other.admitted()).isTrue();
        other.lease().close();
    }

    @Test
    @DisplayName("closing a lease twice does not disturb the quota")
    void doubleCloseDoesNotDisturbTheQuota() {
        ManualClock clock = new ManualClock();
        AdmissionController controller = controller(clock);

        Admission admission = controller.admit(ACME);
        admission.lease().close();
        admission.lease().close();

        for (int i = 0; i < 4; i++) {
            controller.admit(ACME).lease().close();
        }
        assertThat(controller.admit(ACME).admitted())
            .as("5 permits were consumed in total, so the bucket is empty")
            .isFalse();
    }

    @Test
    @DisplayName("a backwards clock jump does not mint tokens")
    void backwardsClockDoesNotMintTokens() {
        ManualClock clock = new ManualClock();
        AdmissionController controller = controller(clock);

        for (int i = 0; i < 5; i++) {
            controller.admit(ACME).lease().close();
        }

        clock.advance(Duration.ofSeconds(-30));   // NTP correction, or a node whose clock moved back

        assertThat(controller.admit(ACME).admitted())
            .as("a node whose clock moved backwards must not refill by subtraction")
            .isFalse();
    }

    @Test
    @DisplayName("a request refused for concurrency hands its rate token back")
    void concurrencyShedRefundsTheRateToken() {
        ManualClock clock = new ManualClock();
        AdmissionController controller = controller(clock, 1);

        Admission held = controller.admit(ACME);
        assertThat(held.admitted()).as("the only slot is free to begin with").isTrue();

        Admission refused = controller.admit(ACME);
        assertThat(refused.admitted()).isFalse();
        assertThat(refused.overloaded())
            .as("refused for capacity, not for quota - the two are different answers to the client")
            .isTrue();

        held.lease().close();   // the slot comes back

        int admitted = 0;
        for (int i = 0; i < 5; i++) {
            Admission admission = controller.admit(ACME);
            if (admission.admitted()) {
                admitted++;
                admission.lease().close();
            }
        }

        // Burst is 5. One permit belongs to `held`; one was taken by `refused` and returned with it.
        assertThat(admitted)
            .as("a node that is merely saturated must not spend a token out of a quota shared with "
                + "every other node - the tenant would be rate limited by a machine that never "
                + "refused it")
            .isEqualTo(4);
    }
}