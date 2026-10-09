package com.saas.pricing.core.model.subscription;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subscription lifecycle.
 *
 * <p>The property that carries the most weight: a cancelled subscription cannot be resumed. A
 * subscription that can come back from cancellation is how a customer keeps access they stopped
 * paying for, and it is unenforceable afterwards because the cancellation has already produced a
 * credit note.
 */
class SubscriptionTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final CustomerId CUSTOMER = CustomerId.of("c1");
    private static final PlanCode PLAN = PlanCode.of("PRO");

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    // A 30-day period, so "halfway" is exactly 0.5 rather than 16/31.
    private static final Instant END = Instant.parse("2026-10-31T00:00:00Z");
    private static final Instant CREATED = START;
    private static final Instant MID = Instant.parse("2026-10-16T00:00:00Z");

    private static Subscription active() {
        return Subscription.active("sub-1", TENANT, CUSTOMER, PLAN, START, END, CREATED);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("a new subscription is ACTIVE and live")
        void startsActive() {
            var sub = active();

            assertThat(sub.status()).isEqualTo(Subscription.Status.ACTIVE);
            assertThat(sub.status().isLive()).isTrue();
            assertThat(sub.status().isTerminal()).isFalse();
        }

        @Test
        @DisplayName("pause and resume round-trip through PAST_DUE")
        void pauseResumeRoundTrip() {
            var paused = active().pause(MID);
            assertThat(paused.status()).isEqualTo(Subscription.Status.PAUSED);

            var resumed = paused.resume();
            assertThat(resumed.status()).isEqualTo(Subscription.Status.ACTIVE);

            var pastDue = active().markPastDue();
            assertThat(pastDue.status()).isEqualTo(Subscription.Status.PAST_DUE);
            assertThat(pastDue.resume().status()).isEqualTo(Subscription.Status.ACTIVE);
        }

        @Test
        @DisplayName("a trial ends into billable state and drops its trial date")
        void trialEndsIntoActive() {
            var trialing = Subscription.trialing("sub-t", TENANT, CUSTOMER, PLAN, START, END, END, CREATED);
            assertThat(trialing.status()).isEqualTo(Subscription.Status.TRIALING);
            assertThat(trialing.trialEndsAt()).contains(END);

            var activeNow = trialing.completeTrial(END);
            assertThat(activeNow.status()).isEqualTo(Subscription.Status.ACTIVE);
            assertThat(activeNow.trialEndsAt()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Renewal")
    class Renewal {

        @Test
        @DisplayName("renewal advances the period and keeps the state")
        void renewalAdvancesPeriod() {
            var renewed = active().renew(END);

            assertThat(renewed.currentPeriodStart()).isEqualTo(END);
            assertThat(renewed.currentPeriodEnd()).isEqualTo(Instant.parse("2026-11-30T00:00:00Z"));
            assertThat(renewed.status()).isEqualTo(Subscription.Status.ACTIVE);
        }

        /**
         * The new period starts on the subscription's own boundary, not on when the job happened to run.
         *
         * <p>A renewal job that runs at 09:07 every month would otherwise drag every billing
         * boundary forward by seven minutes on every cycle, compounding without bound — and the
         * customer's invoice dates would quietly stop matching their contract.
         */
        @Test
        @DisplayName("a late-running renewal does not drag the billing boundary")
        void renewalDoesNotDriftWithTheJobClock() {
            var ranAt = Instant.parse("2026-10-31T09:07:23Z");

            var first = active().renew(ranAt);
            var second = first.renew(ranAt.plusSeconds(400));

            assertThat(first.currentPeriodStart())
                .as("the period must begin on the boundary, not on the job's wall clock")
                .isEqualTo(END);
            assertThat(first.currentPeriodEnd()).isEqualTo(Instant.parse("2026-11-30T00:00:00Z"));
            assertThat(second.currentPeriodStart()).isEqualTo(Instant.parse("2026-11-30T00:00:00Z"));
            assertThat(second.currentPeriodEnd()).isEqualTo(Instant.parse("2026-12-30T00:00:00Z"));
        }

        /**
         * Each subscription keeps the cadence it already has.
         *
         * <p>The old signature took one period length for the whole batch, so renewing an annual plan
         * with a monthly length billed the customer twelve times a year — and nothing in the code
         * could tell, because every row was given the period it was handed.
         */
        @Test
        @DisplayName("an annual plan renews annually, whatever the job was given")
        void renewalPreservesOwnCadence() {
            var yearEnd = Instant.parse("2027-01-01T00:00:00Z");
            var annual = Subscription.active("sub-annual", TENANT, CUSTOMER, PLAN,
                Instant.parse("2026-01-01T00:00:00Z"), yearEnd, CREATED);

            var renewed = annual.renew(yearEnd);

            assertThat(renewed.currentPeriodStart()).isEqualTo(yearEnd);
            assertThat(renewed.currentPeriodEnd())
                .as("an annual plan must not be re-billed monthly")
                .isEqualTo(Instant.parse("2028-01-01T00:00:00Z"));
        }

        /**
         * A finished trial becomes billable instead of renewing forever.
         *
         * <p>Previously a trialing subscription reached its period end and produced a record with
         * {@code TRIALING} and no trial date, which the invariant check refused outright — so the
         * renewal job threw on the first trial it met and left every later subscription unbilled.
         */
        @Test
        @DisplayName("a finished trial becomes billable at renewal")
        void trialConvertsToBillableOnRenewal() {
            var trialing = Subscription.trialing("sub-t", TENANT, CUSTOMER, PLAN, START, END, END, CREATED);

            var renewed = trialing.renew(END);

            assertThat(renewed.status())
                .as("a trial that survives its own boundary is a customer who is never charged")
                .isEqualTo(Subscription.Status.ACTIVE);
            assertThat(renewed.trialEndsAt()).isEmpty();
            assertThat(renewed.currentPeriodStart()).isEqualTo(END);
        }

        @Test
        @DisplayName("a trial still running is not renewed")
        void unfinishedTrialIsNotRenewed() {
            var trialEnd = Instant.parse("2026-11-30T00:00:00Z");
            var trialing = Subscription.trialing("sub-t", TENANT, CUSTOMER, PLAN, START, END,
                trialEnd, CREATED);

            assertThatThrownBy(() -> trialing.renew(END))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trialling until");
        }

        /**
         * A paused subscription must not have its period advanced.
         *
         * <p>Two reasons, and both are real. Advancing it burns the paid time left in the period
         * while the customer is not being charged; and because renewal dropped {@code pausedAt}
         * while keeping the status, the write was refused by the database's own check constraint —
         * so a paused row could take renewal down rather than merely being mishandled.
         */
        @Test
        @DisplayName("a paused subscription does not renew")
        void pausedSubscriptionDoesNotRenew() {
            var paused = active().pause(MID);

            assertThatThrownBy(() -> paused.renew(END))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not renewable");
        }

        /** Collection, not the renewal job, decides what happens to a failed payment. */
        @Test
        @DisplayName("a past-due subscription is left to collection")
        void pastDueIsNotRenewedByTheJob() {
            var pastDue = active().markPastDue();

            assertThatThrownBy(() -> pastDue.renew(END))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collection owns this subscription");
        }

        @Test
        @DisplayName("renewal is due only once the period has actually ended")
        void renewalDueOnlyAfterPeriodEnd() {
            assertThat(active().isRenewalDue(MID)).isFalse();
            assertThat(active().isRenewalDue(END)).isTrue();
            assertThat(active().isRenewalDue(END.plusSeconds(1))).isTrue();
        }
    }

    @Nested
    @DisplayName("Version")
    class Versioning {

        /**
         * Every transition advances the version, and the version is the event sequence.
         *
         * <p>This exists because the event id used to be built from a constant. The outbox refuses a
         * duplicate id carrying different content and silently drops an identical one, so every
         * announcement after the first for a topic was discarded — the row was correct and no
         * subscriber ever heard about the change.
         */
        @Test
        @DisplayName("every transition advances the version")
        void everyTransitionAdvancesVersion() {
            assertThat(active().version()).isZero();

            var paused = active().pause(MID);
            assertThat(paused.version()).isEqualTo(1);

            var resumed = paused.resume();
            assertThat(resumed.version()).isEqualTo(2);

            var renewed = resumed.renew(END);
            assertThat(renewed.version()).isEqualTo(3);

            var cancelled = renewed.queueCancellationAtPeriodEnd();
            assertThat(cancelled.version()).isEqualTo(4);
        }

        /** Two pause/resume cycles in one period must not repeat a version. */
        @Test
        @DisplayName("repeated transitions never repeat a version")
        void repeatedTransitionsGetDistinctVersions() {
            var firstPause = active().pause(MID);
            var firstResume = firstPause.resume();
            var secondPause = firstResume.pause(MID);
            var secondResume = secondPause.resume();

            assertThat(java.util.List.of(firstPause.version(), firstResume.version(),
                    secondPause.version(), secondResume.version()))
                .as("pause, resume, pause, resume all land on states seen before; only a "
                    + "monotonic version keeps their events distinct")
                .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        @Test
        @DisplayName("cancelling at the period end takes effect at renewal, not now")
        void cancelAtPeriodEndTakesEffectAtRenewal() {
            var queued = active().queueCancellationAtPeriodEnd();

            assertThat(queued.isCancellingAtPeriodEnd()).isTrue();
            assertThat(queued.status())
                .as("service continues to the end of a period already paid for")
                .isEqualTo(Subscription.Status.ACTIVE);

            var renewed = queued.renew(END);
            assertThat(renewed.status()).isEqualTo(Subscription.Status.CANCELED);
            assertThat(renewed.canceledAt()).contains(END);
            assertThat(renewed.isCancellingAtPeriodEnd())
                .as("a cancellation already taken effect cannot still be queued")
                .isFalse();
        }

        @Test
        @DisplayName("a cancelled subscription cannot be resumed")
        void cancelledCannotResume() {
            var cancelled = active().cancelImmediately(MID);

            assertThat(cancelled.status().isTerminal()).isTrue();
            assertThatThrownBy(cancelled::resume)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already produced a credit note");
        }

        @Test
        @DisplayName("a cancelled subscription cannot be cancelled again or renewed")
        void terminalIsAbsorbing() {
            var cancelled = active().cancelImmediately(MID);

            assertThatThrownBy(() -> cancelled.cancelImmediately(MID)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cancelled.expire(MID)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cancelled.renew(END)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cancelled.pause(MID)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an expired subscription is terminal too")
        void expiredIsTerminal() {
            var expired = active().expire(END);

            assertThat(expired.status()).isEqualTo(Subscription.Status.EXPIRED);
            assertThatThrownBy(expired::resume).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Construction invariants")
    class Invariants {

        @Test
        @DisplayName("a live subscription cannot carry a cancellation timestamp")
        void liveCannotCarryCancellation() {
            assertThatThrownBy(() -> new Subscription("s", TENANT, CUSTOMER, PLAN,
                Subscription.Status.ACTIVE, CREATED, START, END, java.util.Optional.empty(),
                false, Optional.of(MID), java.util.Optional.empty(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot carry a cancellation timestamp");
        }

        @Test
        @DisplayName("a terminal subscription must record when it ended")
        void terminalMustRecordEnd() {
            assertThatThrownBy(() -> new Subscription("s", TENANT, CUSTOMER, PLAN,
                Subscription.Status.CANCELED, CREATED, START, END, java.util.Optional.empty(),
                false, java.util.Optional.empty(), java.util.Optional.empty(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must record when it ended");
        }

        @Test
        @DisplayName("a trial with no end date is refused")
        void trialNeedsEndDate() {
            assertThatThrownBy(() -> new Subscription("s", TENANT, CUSTOMER, PLAN,
                Subscription.Status.TRIALING, CREATED, START, END, java.util.Optional.empty(),
                false, java.util.Optional.empty(), java.util.Optional.empty(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a trial end");
        }

        /**
         * A paused subscription must record when it was paused.
         *
         * <p>This mirrors {@code ck_subscription_pause_recorded} in V14. It was enforced only by the
         * database, so the aggregate happily produced a PAUSED row with no pause timestamp and the
         * failure surfaced at flush time as a constraint violation — far from the code that caused it.
         */
        @Test
        @DisplayName("a paused subscription must record when it was paused")
        void pausedNeedsPauseTimestamp() {
            assertThatThrownBy(() -> new Subscription("s", TENANT, CUSTOMER, PLAN,
                Subscription.Status.PAUSED, CREATED, START, END, java.util.Optional.empty(),
                false, java.util.Optional.empty(), java.util.Optional.empty(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must record when it was paused");
        }

        @Test
        @DisplayName("a negative version is refused")
        void versionCannotBeNegative() {
            assertThatThrownBy(() -> new Subscription("s", TENANT, CUSTOMER, PLAN,
                Subscription.Status.ACTIVE, CREATED, START, END, java.util.Optional.empty(),
                false, java.util.Optional.empty(), java.util.Optional.empty(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version cannot be negative");
        }

        @Test
        @DisplayName("resuming something that is already active is refused")
        void resumeRequiresPaused() {
            assertThatThrownBy(active()::resume)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAUSED or PAST_DUE");
        }

        /**
         * Pausing an already-paused subscription reports a change that did not happen.
         *
         * <p>It used to succeed, producing an identical state and therefore an identical event — which
         * the outbox discarded. The operator's action appeared to work and nothing was recorded.
         */
        @Test
        @DisplayName("pausing an already-paused subscription is refused")
        void pauseRequiresNotAlreadyPaused() {
            var paused = active().pause(MID);

            assertThatThrownBy(() -> paused.pause(MID.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already paused");
        }

        @Test
        @DisplayName("marking an already past-due subscription is refused")
        void pastDueRequiresNotAlreadyPastDue() {
            var pastDue = active().markPastDue();

            assertThatThrownBy(pastDue::markPastDue)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already past due");
        }
    }

    @Nested
    @DisplayName("Proration input")
    class Proration {

        @Test
        @DisplayName("halfway through the period leaves half the term unused")
        void remainingFractionMidPeriod() {
            assertThat(active().remainingPeriodFraction(MID))
                .isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("at the period end nothing remains")
        void remainingAtPeriodEnd() {
            assertThat(active().remainingPeriodFraction(END))
                .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("past the period end the fraction clamps to zero rather than going negative")
        void remainingClampsAtZero() {
            // A negative fraction would make a cancellation credit hand the customer money.
            assertThat(active().remainingPeriodFraction(END.plusSeconds(86400)))
                .isEqualByComparingTo("0");
        }
    }
}