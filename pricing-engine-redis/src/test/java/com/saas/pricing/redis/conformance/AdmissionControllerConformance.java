package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.core.spi.AdmissionController.Admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behavioural contract every {@link AdmissionController} must satisfy.
 *
 * <p>The reason this exists is drift between the local and distributed limiters. They are
 * interchangeable only if they admit the same requests for the same instants, and the tempting
 * shortcut — Redis's own Java guide refills on discrete intervals — makes them differ at the margin
 * without anyone noticing. A limiter that is subtly more permissive in production than in the unit
 * test is not a limiter anyone can rely on.</p>
 */
@DisplayName("AdmissionController conformance")
abstract class AdmissionControllerConformance {

    protected static final TenantId ACME = new TenantId("acme");
    protected static final TenantId GLOBEX = new TenantId("globex");

    /** Burst of 5, refilling at 5 per second, with a concurrency ceiling high enough not to interfere. */
    protected abstract AdmissionController controller(ManualClock clock);

    /** A clock the test drives, so refill behaviour is deterministic rather than timing-dependent. */
    static final class ManualClock {
        private long millis;

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
}