package com.saas.pricing.starter;

import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.invoice.ProrationAdjustment;
import com.saas.pricing.core.model.invoice.ProrationCalculator;
import com.saas.pricing.core.model.invoice.ProrationReason;
import com.saas.pricing.core.model.subscription.Subscription;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Subscription lifecycle transitions, and the money each one implies.
 *
 * <h2>Why transitions and proration belong together</h2>
 * A lifecycle change and its financial consequence are the same decision. Cancelling immediately
 * credits the unused part of the period; cancelling at the period boundary credits nothing, because
 * service continues to the end of a period already paid for. Splitting the two across services is
 * how a cancellation ends up issuing a credit note for time the customer actually used.
 *
 * <p>So this service returns the resulting {@link Subscription} <em>and</em>, where the money is
 * owed, the {@link ProrationAdjustment} lines it produced. A caller cannot take the state change
 * and silently skip the adjustment.
 */
public class SubscriptionLifecycleService {

    private final Clock clock;

    public SubscriptionLifecycleService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /** What a transition produced: the new state, plus any invoice lines it implies. */
    public record Outcome(
        Subscription subscription,
        List<ProrationAdjustment> adjustments,
        ProrationReason reason
    ) {
        public Outcome {
            adjustments = adjustments == null ? List.of() : List.copyOf(adjustments);
        }

        /** Net effect on the next invoice: positive the customer owes more, negative they are owed. */
        public Optional<Money> netAdjustment() {
            if (adjustments.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(ProrationAdjustment.netOf(adjustments));
        }

        public boolean producesInvoiceLines() {
            return !adjustments.isEmpty();
        }
    }

    /** Cancels immediately, crediting the unused remainder of the paid period. */
    public Outcome cancelImmediately(Subscription subscription, String itemCode,
                                     Money fullPeriodPrice) {
        Instant at = clock.instant();
        Subscription canceled = subscription.cancelImmediately(at);
        BigDecimal remaining = subscription.remainingPeriodFraction(at);

        List<ProrationAdjustment> adjustments = remaining.signum() <= 0
            ? List.of()
            : List.of(new ProrationAdjustment(ProrationAdjustment.Kind.CREDIT, itemCode,
                subscription.planCode().value(),
                fullPeriodPrice.times(remaining).roundToCurrency(),
                remaining, at, subscription.currentPeriodEnd(),
                subscription.currentPeriodStart(), subscription.currentPeriodEnd(),
                false, ProrationReason.CANCELLED_EARLY));

        return new Outcome(canceled, adjustments, ProrationReason.CANCELLED_EARLY);
    }

    /**
     * Queues cancellation for the period boundary.
     *
     * <p>Produces no adjustment: the customer keeps what they paid for, which is the entire point
     * of choosing end-of-period cancellation over an immediate one.
     */
    public Outcome cancelAtPeriodEnd(Subscription subscription) {
        return new Outcome(subscription.queueCancellationAtPeriodEnd(), List.of(),
            ProrationReason.CANCELLED_EARLY);
    }

    /** A trial ending produces the next complete period's charge, which is not a proration. */
    public Outcome completeTrial(Subscription subscription, String itemCode,
                                 String newPlanCode, Money fullPeriodPrice,
                                 Instant periodStart, Instant periodEnd) {
        Subscription active = subscription.completeTrial(clock.instant());
        List<ProrationAdjustment> adjustments = List.of(new ProrationAdjustment(
            ProrationAdjustment.Kind.DEBIT, itemCode, newPlanCode,
            fullPeriodPrice.roundToCurrency(), BigDecimal.ONE,
            periodStart, periodEnd, periodStart, periodEnd,
            false, ProrationReason.SUBSCRIPTION_STARTED));
        return new Outcome(active, adjustments, ProrationReason.SUBSCRIPTION_STARTED);
    }

    /** A mid-period plan change produces the credit/debit pair. */
    public Outcome changePlan(Subscription subscription, String itemCode,
                              Money oldPrice, String newPlanCode, Money newPrice,
                              Instant effectiveAt, Instant periodStart, Instant periodEnd) {
        List<ProrationAdjustment> adjustments = ProrationCalculator.forPlanChange(
            itemCode, subscription.planCode().value(), oldPrice,
            newPlanCode, newPrice, periodStart, periodEnd, effectiveAt,
            fullPeriodPriceCurrency(oldPrice), ProrationReason.PLAN_CHANGED);
        return new Outcome(subscription, adjustments, ProrationReason.PLAN_CHANGED);
    }

    private static com.saas.pricing.core.model.CurrencyUnit fullPeriodPriceCurrency(Money price) {
        return price.currency();
    }
}