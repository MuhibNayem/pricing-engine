package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.ProrationWindow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Produces the two-sided proration for a mid-period plan change.
 *
 * <h2>The rule</h2>
 * A change at time {@code T} inside a billing period produces:
 *
 * <pre>
 *   CREDIT  unused time on the OLD plan  = oldPrice x (periodEnd - T) / (periodEnd - periodStart)
 *   DEBIT   remaining time on the NEW plan = newPrice x (periodEnd - T) / (periodEnd - periodStart)
 * </pre>
 *
 * <p>Neither side settles itself. The credit is not auto-refunded and the debit is not auto-billed;
 * both surface as invoice items and the host decides. A rating engine that refunds on its own is
 * making a commercial decision the merchant has not consented to.
 *
 * <h2>Backdating</h2>
 * When a change is recorded with {@code recordedAt} later than the moment it took effect
 * ({@code effectiveAt}), the same math runs against {@code effectiveAt} - the customer gets the
 * credit and is charged the debit for the period that actually applied, not the one we noticed it
 * in.
 */
public final class ProrationCalculator {

    private ProrationCalculator() {
        // static utility
    }

    /**
     * Calculates the pair for a plan change.
     *
     * @param itemCode     the metered item being changed
     * @param oldPlanCode  plan the customer was on
     * @param oldPrice     full-period price of the old plan for this item
     * @param newPlanCode  plan the customer moves to
     * @param newPrice     full-period price of the new plan for this item
     * @param periodStart  inclusive start of the billing period
     * @param periodEnd    exclusive end of the billing period
     * @param effectiveAt  when the change takes effect for the customer (valid time)
     * @param currency     currency both prices are expressed in
     * @return exactly two adjustments, credit first, when the change is strictly inside the period
     */
    public static List<ProrationAdjustment> forPlanChange(
        String itemCode, String oldPlanCode, Money oldPrice,
        String newPlanCode, Money newPrice,
        Instant periodStart, Instant periodEnd, Instant effectiveAt, CurrencyUnit currency) {
        return forPlanChange(itemCode, oldPlanCode, oldPrice, newPlanCode, newPrice,
            periodStart, periodEnd, effectiveAt, currency, ProrationReason.PLAN_CHANGED);
    }

    /**
     * Calculates the pair for an arbitrary operation, carrying its classification.
     *
     * @param reason the operation that caused this change; determines whether the result counts as
     *               a proration for downstream revenue recognition
     */
    public static List<ProrationAdjustment> forPlanChange(
        String itemCode, String oldPlanCode, Money oldPrice,
        String newPlanCode, Money newPrice,
        Instant periodStart, Instant periodEnd, Instant effectiveAt, CurrencyUnit currency,
        ProrationReason reason) {

        Objects.requireNonNull(itemCode, "itemCode cannot be null");
        Objects.requireNonNull(oldPrice, "oldPrice cannot be null");
        Objects.requireNonNull(newPrice, "newPrice cannot be null");
        Objects.requireNonNull(periodStart, "periodStart cannot be null");
        Objects.requireNonNull(periodEnd, "periodEnd cannot be null");
        Objects.requireNonNull(effectiveAt, "effectiveAt cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");

        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("periodEnd must be after periodStart");
        }
        Objects.requireNonNull(reason, "reason cannot be null");
        if (!reason.producesBoth()) {
            throw new IllegalArgumentException(
                "Operation " + reason + " does not produce a credit/debit pair; a plan change "
                    + "produces both sides");
        }
        if (!oldPrice.currency().equals(currency) || !newPrice.currency().equals(currency)) {
            throw new IllegalArgumentException(
                "Both plan prices must be expressed in " + currency.code()
                    + "; a proration across currencies needs a stored rate, which belongs in FxBooking");
        }

        // The covered span is [max(effectiveAt, periodStart), periodEnd) - the part of the period that
        // the NEW price applies to. Clamping the end to effectiveAt instead would collapse the
        // span to zero and produce nothing at all.
        if (!effectiveAt.isBefore(periodEnd)) {
            // A change effective at or after the period end is simply not this period's business.
            return List.of();
        }
        Instant clampedStart = effectiveAt.isBefore(periodStart) ? periodStart : effectiveAt;
        Instant clampedEnd = periodEnd;

        BigDecimal periodSeconds = BigDecimal.valueOf(
            java.time.Duration.between(periodStart, periodEnd).toSeconds());
        BigDecimal remainingSeconds = BigDecimal.valueOf(
            java.time.Duration.between(clampedStart, periodEnd).toSeconds());
        BigDecimal factor = remainingSeconds.divide(periodSeconds, ProrationWindow.RATIO_SCALE,
            RoundingMode.HALF_EVEN);

        Money credit = Money.of(oldPrice.amount().multiply(factor), currency).roundToCurrency();
        Money debit = Money.of(newPrice.amount().multiply(factor), currency).roundToCurrency();

        List<ProrationAdjustment> adjustments = new ArrayList<>(2);
        adjustments.add(new ProrationAdjustment(ProrationAdjustment.Kind.CREDIT, itemCode, oldPlanCode,
            credit.abs(), factor, clampedStart, periodEnd, periodStart, periodEnd, false, reason));
        adjustments.add(new ProrationAdjustment(ProrationAdjustment.Kind.DEBIT, itemCode, newPlanCode,
            debit.abs(), factor, clampedStart, periodEnd, periodStart, periodEnd, false, reason));
        return adjustments;
    }

    /**
     * The net effect of a plan change: the new price minus the old, over the remaining fraction.
     *
     * <p>An upgrade nets positive (the customer owes more), a downgrade nets negative.
     */
    public static Money netChange(List<ProrationAdjustment> adjustments) {
        return ProrationAdjustment.netOf(adjustments);
    }

    /**
     * Proration for a change recorded now but effective earlier.
     *
     * <p>Same arithmetic, anchored on {@code effectiveAt} rather than on when the record was
     * written, so a backdated change is charged for the period that actually applied.
     */
    public static List<ProrationAdjustment> forBackdatedChange(
        String itemCode, String oldPlanCode, Money oldPrice,
        String newPlanCode, Money newPrice,
        Instant periodStart, Instant periodEnd, Instant effectiveAt, Instant recordedAt,
        CurrencyUnit currency) {

        Objects.requireNonNull(recordedAt, "recordedAt cannot be null");
        if (effectiveAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException(
                "effectiveAt " + effectiveAt + " cannot be after recordedAt " + recordedAt);
        }
        return forPlanChange(itemCode, oldPlanCode, oldPrice, newPlanCode, newPrice,
            periodStart, periodEnd, effectiveAt, currency);
    }
}