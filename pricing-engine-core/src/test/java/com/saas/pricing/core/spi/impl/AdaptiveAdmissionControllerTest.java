package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController.Admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the behaviour that separates an adaptive ceiling from a fixed one.
 *
 * <p>The algorithm's value is entirely in its response curve, so the tests drive measured
 * round-trip times rather than asserting on internals.</p>
 */
@DisplayName("AdaptiveAdmissionController")
class AdaptiveAdmissionControllerTest {

    private static final TenantId ACME = new TenantId("acme");

    private static final long PERMIT = Duration.ofMinutes(1).toNanos();

    /** Feeds {@code count} completions of {@code rttNanos} into the controller. */
    private static void feed(AdaptiveAdmissionController controller, int count, long rttNanos) {
        for (int i = 0; i < count; i++) {
            controller.observeCompletionNanos(rttNanos);
        }
    }

    @Test
    @DisplayName("never leaves its configured range in either direction")
    void staysWithinRange() {
        var controller = new AdaptiveAdmissionController(PERMIT, Duration.ofMinutes(1), PERMIT, 2, 8);

        assertThat(controller.currentLimit()).isEqualTo(2);

        // Sustained latency with no divergence is, correctly, read as "no queueing" - so the limit
        // grows. The guarantee under test is that it stops at maxLimit rather than running away.
        feed(controller, 500, 1_000_000_000L);
        assertThat(controller.currentLimit()).isLessThanOrEqualTo(8);

        // And the floor holds when the divergence points the other way.
        var shrinking = new AdaptiveAdmissionController(PERMIT, Duration.ofMinutes(1), PERMIT, 2, 8);
        feed(shrinking, 40, 1_000L);
        feed(shrinking, 200, 900_000_000L);
        assertThat(shrinking.currentLimit()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("grows the limit while latency is stable")
    void growsWhenLatencyIsHealthy() {
        var controller = new AdaptiveAdmissionController(PERMIT, Duration.ofMinutes(1), PERMIT, 2, 20);

        feed(controller, 20, 1_000_000L);
        int afterFirstWindow = controller.currentLimit();
        feed(controller, 20, 1_000_000L);

        assertThat(controller.currentLimit())
            .as("a healthy engine should be allowed to grow, not held at the floor")
            .isGreaterThan(afterFirstWindow);
    }

    @Test
    @DisplayName("shrinks when latency climbs and stays there")
    void shrinksUnderSustainedInflation() {
        var controller = new AdaptiveAdmissionController(PERMIT, Duration.ofMinutes(1), PERMIT, 2, 20);

        // Establish a healthy baseline and let the limit grow off the floor, so there is somewhere
        // to shrink to.
        feed(controller, 40, 1_000_000L);
        int grown = controller.currentLimit();
        assertThat(grown).as("precondition: the limit must have room to fall").isGreaterThan(2);

        feed(controller, 200, 500_000_000L);   // sustained 500ms

        assertThat(controller.currentLimit())
            .as("sustained inflation must reduce the ceiling, from %d", grown)
            .isLessThan(grown);
    }

    @Test
    @DisplayName("raises the ceiling in lockstep with observed work")
    void enforcesTheAdaptedCeiling() {
        var controller = new AdaptiveAdmissionController(PERMIT, Duration.ofMinutes(1), PERMIT, 2, 20);
        AtomicLong clock = new AtomicLong();

        var timed = new AdaptiveAdmissionController(PERMIT, Duration.ofMinutes(1), PERMIT, 2, 20,
            AdaptiveAdmissionController.DEFAULT_ALPHA, AdaptiveAdmissionController.DEFAULT_BETA, clock::get);

        // Hold leases open so in-flight actually accumulates against the adapted ceiling.
        int held = 0;
        for (int i = 0; i < 2; i++) {
            Admission admission = timed.admit(ACME);
            assertThat(admission.admitted()).as("lease %d", i).isTrue();
            held++;
        }

        Admission shed = timed.admit(ACME);
        assertThat(shed.admitted()).isFalse();
        assertThat(shed.overloaded())
            .as("the ceiling, not the quota, is what rejects the third of two permitted")
            .isTrue();

        assertThat(controller.currentLimit()).isEqualTo(2);
        assertThat(held).isEqualTo(2);
    }

    @Test
    @DisplayName("a non-positive duration is ignored rather than treated as instant")
    void ignoresNonPositiveSamples() {
        var controller = new AdaptiveAdmissionController(PERMIT, Duration.ofMinutes(1), PERMIT, 2, 20);

        feed(controller, 50, 0);
        feed(controller, 50, -1);

        assertThat(controller.currentLimit())
            .as("a clock reading zero or negative is noise, not evidence of instant completion")
            .isEqualTo(2);
    }
}