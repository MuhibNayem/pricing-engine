package com.saas.pricing.core.model.invoice;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One side of a mid-period plan change.
 *
 * <h2>Why a change produces two of these, not one</h2>
 * A plan change is not a single adjustment. Upgrading a $10 plan to a $20 plan halfway through the
 * period produces <strong>two</strong> entries: a <em>credit</em> for the unused time on the old
 * plan, and a <em>debit</em> for the remaining time on the new one. Emitting only one of them
 * either overcharges the customer for time they did not receive, or undercharges for time they did.
 *
 * <p>Which is why {@link Kind} is part of the record: a caller that only looks at the signed amount
 * will silently mishandle the credit side, because a negative amount on an invoice and a negative
 * amount that means "we owe the customer this" are opposite things.
 *
 * <p><strong>Neither side settles itself.</strong> A credit is not automatically refunded and a
 * debit is not automatically charged; both land as invoice items for the customer to see, and it
 * is the host's decision whether to refund or bill them. That is a commercial decision, not a
 * computational one, and a rating engine that auto-refunds a customer is taking the decision for
 * them.
 *
 * @param kind          CREDIT (reduces what is owed) or DEBIT (increases what is owed)
 * @param itemCode      the plan item this applies to
 * @param sourcePlanCode plan the amount was priced under
 * @param amount        always positive; direction is carried by {@link #signedAmount()}, not by sign
 * @param currency      currency of the amount
 * @param factor        fraction of the period this covers
 * @param effectiveFrom inclusive start of the covered span
 * @param effectiveTo   exclusive end of the covered span
 * @param periodStart   start of the billing period
 * @param periodEnd     end of the billing period
 * @param automatic     always false: neither side self-settles
 */
public record ProrationAdjustment(
    Kind kind,
    String itemCode,
    String sourcePlanCode,
    Money amount,
    BigDecimal factor,
    Instant effectiveFrom,
    Instant effectiveTo,
    Instant periodStart,
    Instant periodEnd,
    boolean automatic,
    ProrationReason reason
) implements Serializable {

    /** Which side of the change this is. */
    public enum Kind {
        /** Reduces what the customer owes: unused time on the previous plan. */
        CREDIT,
        /** Increases what the customer owes: remaining time on the new plan. */
        DEBIT
    }

    public ProrationAdjustment {
        Objects.requireNonNull(kind, "kind cannot be null");
        Objects.requireNonNull(itemCode, "itemCode cannot be null");
        Objects.requireNonNull(sourcePlanCode, "sourcePlanCode cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
        Objects.requireNonNull(factor, "factor cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(effectiveTo, "effectiveTo cannot be null");
        Objects.requireNonNull(periodStart, "periodStart cannot be null");
        Objects.requireNonNull(periodEnd, "periodEnd cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("periodEnd must be after periodStart");
        }
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("A proration span must be non-empty");
        }
        if (effectiveFrom.isBefore(periodStart) || effectiveTo.isAfter(periodEnd)) {
            throw new IllegalArgumentException(
                "Proration span " + effectiveFrom + ".." + effectiveTo
                    + " falls outside the billing period " + periodStart + ".." + periodEnd);
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                "Store the magnitude as a positive amount; direction belongs to the kind, not the sign");
        }
        if (factor.signum() < 0 || factor.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("factor must be between 0 and 1, got " + factor);
        }
        if (automatic) {
            throw new IllegalArgumentException(
                "A proration adjustment never self-settles: refunding a credit or billing a debit is a "
                    + "commercial decision for the host, not the rating engine");
        }
        // The classification must match the side of the pair it belongs to, or downstream revenue
        // recognition will book a subscription start as a mid-cycle adjustment.
        if (reason.producesCredit() && kind != Kind.CREDIT) {
            throw new IllegalArgumentException(
                "Operation " + reason + " only produces a credit, but a DEBIT was supplied");
        }
        if (reason.producesDebit() && kind != Kind.DEBIT && !reason.producesBoth()) {
            throw new IllegalArgumentException(
                "Operation " + reason + " does not produce a debit, but one was supplied");
        }
        if (kind == Kind.DEBIT && !reason.producesDebit()) {
            throw new IllegalArgumentException(
                "Operation " + reason + " cannot carry a debit line");
        }
    }

    /** The amount with the direction applied: negative for a credit, positive for a debit. */
    public Money signedAmount() {
        BigDecimal signed = kind == Kind.CREDIT
            ? amount.amount().negate()
            : amount.amount();
        return Money.of(signed, amount.currency());
    }

    public boolean isCredit() {
        return kind == Kind.CREDIT;
    }

    /**
     * Renders as an invoice line item carrying the signed amount.
     *
     * <p>Built directly rather than via {@code InvoiceLineItem.of}, because that factory computes
     * {@code quantity x unitPrice} and would discard the proration factor; here the signed amount is
     * already known.
     */
    public InvoiceLineItem toLineItem() {
        String description = (isCredit() ? "Unused credit: " : "Remaining charge: ")
            + sourcePlanCode + " " + itemCode;
        return new InvoiceLineItem(itemCode, description, factor,
            Money.of(BigDecimal.ONE, amount.currency()), signedAmount(), "",
            Map.of("kind", kind.name(),
                "sourcePlanCode", sourcePlanCode,
                "prorationReason", reason.name(),
                "isProration", String.valueOf(reason.isProration())));
    }

    /** Net effect of a pair on what the customer owes: debit minus credit. */
    public static Money netOf(java.util.List<ProrationAdjustment> adjustments) {
        if (adjustments.isEmpty()) {
            throw new IllegalArgumentException("netOf requires at least one adjustment");
        }
        CurrencyUnit currency = adjustments.getFirst().amount().currency();
        Money net = Money.zero(currency);
        for (ProrationAdjustment adjustment : adjustments) {
            net = net.plus(adjustment.signedAmount());
        }
        return net.roundToCurrency();
    }
}