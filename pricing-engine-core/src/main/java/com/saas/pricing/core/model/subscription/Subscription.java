package com.saas.pricing.core.model.subscription;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A subscription and its lifecycle.
 *
 * <h2>Why the state lives here rather than in a flag</h2>
 * A subscription is what decides <em>whether</em> a charge is a proration, and that answer differs
 * by operation: the identical full-period debit is a proration when the billing anchor moves
 * mid-period and is <em>not</em> a proration when a subscription simply starts. Encoding the
 * lifecycle as an explicit state machine makes that a property of the object rather than something
 * each caller re-derives.
 *
 * <h2>Terminal states are terminal</h2>
 * {@link Status#CANCELED} and {@link Status#EXPIRED} are absorbing. A cancelled subscription that can
 * be "resumed" is how a customer keeps access they stopped paying for — and it is unenforceable
 * afterwards, because the cancellation already produced a credit note.
 *
 * <h2>Why there is a version</h2>
 * Every transition increments it, and it is the sequence number for this subscription's events in
 * the outbox. Without a per-aggregate sequence, anything derived from the state — a period start, a
 * clock reading — eventually repeats: pause, resume, pause again in one billing period all land on
 * the same state, and the outbox (which refuses a duplicate event id carrying different content, and
 * silently drops an identical one) then swallows the second pause. A committed change that no
 * subscriber ever heard about is the hardest class of billing bug to detect, because the row looks
 * correct.
 *
 * <p>Immutable: every transition returns a new subscription.
 *
 * @param subscriptionId   stable identity
 * @param tenantId         owning tenant
 * @param customerId       subscribing customer
 * @param planCode         current plan
 * @param status           lifecycle state
 * @param createdAt        when the subscription was created
 * @param currentPeriodStart inclusive start of the period being charged now
 * @param currentPeriodEnd exclusive end of the period being charged now
 * @param trialEndsAt      when a trial ends, if one is running
 * @param cancelAtPeriodEnd whether a cancellation is queued for the period boundary
 * @param canceledAt       when cancellation took effect
 * @param pausedAt         when it was last paused
 * @param version          transitions applied so far; the sequence number for this aggregate's events
 */
public record Subscription(
    String subscriptionId,
    TenantId tenantId,
    CustomerId customerId,
    PlanCode planCode,
    Status status,
    Instant createdAt,
    Instant currentPeriodStart,
    Instant currentPeriodEnd,
    Optional<Instant> trialEndsAt,
    boolean cancelAtPeriodEnd,
    Optional<Instant> canceledAt,
    Optional<Instant> pausedAt,
    long version
) implements Serializable {

    /** Lifecycle state. */
    public enum Status {
        /** Running a free trial; not yet billable. */
        TRIALING,
        /** Being charged. */
        ACTIVE,
        /** Billing failed and collection is in progress. */
        PAST_DUE,
        /** Billing suspended; the plan is not being charged. */
        PAUSED,
        /** Ended at the customer's request. Terminal. */
        CANCELED,
        /** Ended because it was not renewed. Terminal. */
        EXPIRED;

        /** True when no transition can leave this state. */
        public boolean isTerminal() {
            return this == CANCELED || this == EXPIRED;
        }

        /** True while the subscription still delivers value. */
        public boolean isLive() {
            return this == TRIALING || this == ACTIVE || this == PAST_DUE || this == PAUSED;
        }

        /**
         * True when the renewal job may advance this subscription's billing period.
         *
         * <p>{@code PAUSED} is excluded deliberately: a paused subscription is not being charged, so
         * advancing its period would silently destroy the paid time remaining in it. Advancing a
         * paused row would also clear {@code pausedAt} while keeping the status, which the database
         * refuses — so "just renew everything the query returns" fails outright.
         *
         * <p>{@code PAST_DUE} is excluded because collection, not the renewal job, decides what
         * happens to a subscription whose payment failed.
         */
        public boolean isRenewable() {
            return this == TRIALING || this == ACTIVE;
        }
    }

    public Subscription {
        Objects.requireNonNull(subscriptionId, "subscriptionId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(currentPeriodStart, "currentPeriodStart cannot be null");
        Objects.requireNonNull(currentPeriodEnd, "currentPeriodEnd cannot be null");
        Objects.requireNonNull(trialEndsAt, "trialEndsAt cannot be null");
        Objects.requireNonNull(canceledAt, "canceledAt cannot be null");
        Objects.requireNonNull(pausedAt, "pausedAt cannot be null");

        if (!currentPeriodEnd.isAfter(currentPeriodStart)) {
            throw new IllegalArgumentException("currentPeriodEnd must be after currentPeriodStart");
        }
        if (status == Status.TRIALING && trialEndsAt.isEmpty()) {
            throw new IllegalArgumentException("A TRIALING subscription needs a trial end");
        }
        if (status.isTerminal() && canceledAt.isEmpty()) {
            throw new IllegalArgumentException(
                "A " + status + " subscription must record when it ended");
        }
        // Absorption cuts both ways: a live subscription is not carrying a cancellation timestamp.
        if (!status.isTerminal() && canceledAt.isPresent()) {
            throw new IllegalArgumentException(
                "A " + status + " subscription cannot carry a cancellation timestamp");
        }
        // Mirrors ck_subscription_pause_recorded in V14. Enforcing it here as well means the
        // invariant is rejected at the boundary that creates it, not later by the database.
        if (status == Status.PAUSED && pausedAt.isEmpty()) {
            throw new IllegalArgumentException("A PAUSED subscription must record when it was paused");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative, got " + version);
        }
    }

    /** Starts a subscription immediately, with no trial. */
    public static Subscription active(String id, TenantId tenantId, CustomerId customerId,
                                     PlanCode planCode, Instant periodStart, Instant periodEnd,
                                     Instant createdAt) {
        return new Subscription(id, tenantId, customerId, planCode, Status.ACTIVE, createdAt,
            periodStart, periodEnd, Optional.empty(), false, Optional.empty(), Optional.empty(), 0L);
    }

    /** Starts a trial. Not billable until {@link #completeTrial(Instant)}. */
    public static Subscription trialing(String id, TenantId tenantId, CustomerId customerId,
                                       PlanCode planCode, Instant periodStart, Instant periodEnd,
                                       Instant trialEndsAt, Instant createdAt) {
        return new Subscription(id, tenantId, customerId, planCode, Status.TRIALING, createdAt,
            periodStart, periodEnd, Optional.of(trialEndsAt), false, Optional.empty(),
            Optional.empty(), 0L);
    }

    /** Moves a trial into paid billing. */
    public Subscription completeTrial(Instant at) {
        requireState(Status.TRIALING, "end the trial of");
        return transition(Status.ACTIVE, Optional.empty(), cancelAtPeriodEnd, Optional.empty(),
            Optional.empty());
    }

    /** Marks billing as failed; collection then decides between recovery and cancellation. */
    public Subscription markPastDue() {
        if (status == Status.PAST_DUE) {
            throw new IllegalStateException(
                "Subscription " + subscriptionId + " is already past due; announcing it again "
                    + "would report a change that did not happen");
        }
        requireLive("mark past due");
        return transition(Status.PAST_DUE, trialEndsAt, cancelAtPeriodEnd, Optional.empty(),
            Optional.empty());
    }

    /** Suspends billing. */
    public Subscription pause(Instant at) {
        if (status == Status.PAUSED) {
            throw new IllegalStateException(
                "Subscription " + subscriptionId + " is already paused");
        }
        requireLive("pause");
        return transition(Status.PAUSED, trialEndsAt, cancelAtPeriodEnd, Optional.empty(),
            Optional.of(at));
    }

    /** Resumes a paused or past-due subscription. Refused once terminal. */
    public Subscription resume() {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "Subscription " + subscriptionId + " is " + status
                    + " and cannot be resumed; its cancellation already produced a credit note");
        }
        if (status != Status.PAUSED && status != Status.PAST_DUE) {
            throw new IllegalStateException(
                "Only a PAUSED or PAST_DUE subscription can be resumed, this one is " + status);
        }
        return transition(Status.ACTIVE, trialEndsAt, false, Optional.empty(), Optional.empty());
    }

    /**
     * Queues cancellation at the period boundary.
     *
     * <p>Service continues to the end of a period that has already been paid for, so there is
     * nothing to prorate.
     */
    public Subscription queueCancellationAtPeriodEnd() {
        requireLive("cancel");
        return transition(status, trialEndsAt, true, Optional.empty(), pausedAt);
    }

    /** Cancels immediately. Anything unused in the current period becomes a proration credit. */
    public Subscription cancelImmediately(Instant at) {
        requireLive("cancel");
        return transition(Status.CANCELED, trialEndsAt, false, Optional.of(at), Optional.empty());
    }

    /** Expires at renewal because the customer did not renew. */
    public Subscription expire(Instant at) {
        requireLive("expire");
        return transition(Status.EXPIRED, trialEndsAt, false, Optional.of(at), Optional.empty());
    }

    /**
     * Advances to the next billing period.
     *
     * <p>A queued cancellation takes effect here, which is why it is honoured in {@code renew}
     * rather than at the moment it was requested.
     *
     * <h4>The next period is this subscription's own, anchored on its own boundary</h4>
     * The new period starts at {@link #currentPeriodEnd} and lasts {@link #periodLength()} — the
     * cadence this subscription already has. Anchoring on "now" instead, or taking a single period
     * length from the caller, is wrong twice over: a renewal job that runs at 09:07 every month
     * would drag the billing boundary forward by seven minutes on every single cycle, and an annual
     * plan renewed with a monthly period would silently be charged twelve times a year.
     *
     * <h4>A finished trial becomes billable</h4>
     * A trialing subscription that reaches its period end converts to {@link Status#ACTIVE}.
     * Carrying {@code TRIALING} across the boundary leaves the customer on a trial forever, and
     * clearing {@code trialEndsAt} while keeping the status would not even construct.
     *
     * @param at the instant the renewal job is running, used only to decide whether a trial has run out
     * @throws IllegalStateException if this subscription must not be renewed right now
     */
    public Subscription renew(Instant at) {
        if (status.isTerminal()) {
            throw new IllegalStateException("A " + status + " subscription cannot renew");
        }
        if (!status.isRenewable()) {
            throw new IllegalStateException(
                "A " + status + " subscription is not renewable; "
                    + (status == Status.PAUSED
                        ? "its paid time must not run down while billing is suspended"
                        : "collection owns this subscription until it is resolved"));
        }
        if (status == Status.TRIALING && trialEndsAt.filter(end -> end.isAfter(at)).isPresent()) {
            throw new IllegalStateException(
                "Subscription " + subscriptionId + " is trialling until " + trialEndsAt.get()
                    + " and cannot be renewed yet");
        }

        Instant nextStart = currentPeriodEnd;
        Instant nextEnd = nextStart.plus(periodLength());

        // A queued cancellation takes effect; otherwise a finished trial becomes billable. Carrying
        // TRIALING across the boundary would leave the customer on a free trial indefinitely.
        Status nextStatus = cancelAtPeriodEnd
            ? Status.CANCELED
            : (status == Status.TRIALING ? Status.ACTIVE : status);

        return new Subscription(subscriptionId, tenantId, customerId, planCode, nextStatus, createdAt,
            nextStart, nextEnd, Optional.empty(), false,
            cancelAtPeriodEnd ? Optional.of(nextStart) : Optional.empty(),
            Optional.empty(), version + 1);
    }

    /** True when a queued cancellation takes effect at the next renewal. */
    public boolean isCancellingAtPeriodEnd() {
        return cancelAtPeriodEnd;
    }

    /** This subscription's own billing cadence, taken from the period it is currently in. */
    public Duration periodLength() {
        return Duration.between(currentPeriodStart, currentPeriodEnd);
    }

    /** True when the current period has ended, i.e. renewal is due. */
    public boolean isRenewalDue(Instant at) {
        return !currentPeriodEnd.isAfter(at);
    }

    /**
     * Fraction of the current period still to run at {@code at}.
     *
     * <p>The input to a mid-period cancellation proration: whatever the customer has not used is
     * money they were charged for and did not receive.
     */
    public BigDecimal remainingPeriodFraction(Instant at) {
        long total = periodLength().toSeconds();
        if (total <= 0) {
            throw new IllegalStateException("Current period has non-positive duration");
        }
        long remaining = Duration.between(at, currentPeriodEnd).toSeconds();
        if (remaining <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(remaining)
            .divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_EVEN);
    }

    /** Applies a transition to every other field, advancing the version. */
    private Subscription transition(Status newStatus, Optional<Instant> newTrialEndsAt,
                                    boolean newCancelAtPeriodEnd, Optional<Instant> newCanceledAt,
                                    Optional<Instant> newPausedAt) {
        return new Subscription(subscriptionId, tenantId, customerId, planCode, newStatus, createdAt,
            currentPeriodStart, currentPeriodEnd, newTrialEndsAt, newCancelAtPeriodEnd,
            newCanceledAt, newPausedAt, version + 1);
    }

    private void requireState(Status expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                "Cannot " + action + " a subscription in state " + status + "; expected " + expected);
        }
    }

    private void requireLive(String action) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                "Cannot " + action + " a " + status + " subscription; it is terminal");
        }
    }
}