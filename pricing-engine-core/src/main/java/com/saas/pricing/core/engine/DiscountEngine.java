package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Discount;
import com.saas.pricing.core.model.DiscountStackingRule;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TraceStep;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Executes discount calculation and stacking strategies.
 */
public final class DiscountEngine {

    public record DiscountOutcome(
        Money totalDiscount,
        Money netAmount,
        List<TraceStep> traceSteps
    ) {}

    public DiscountOutcome applyDiscounts(
        Money grossAmount,
        List<Discount> eligibleDiscounts,
        Instant evaluationTime
    ) {
        Objects.requireNonNull(grossAmount, "grossAmount cannot be null");
        Objects.requireNonNull(eligibleDiscounts, "eligibleDiscounts cannot be null");
        Objects.requireNonNull(evaluationTime, "evaluationTime cannot be null");

        if (eligibleDiscounts.isEmpty() || grossAmount.isZero()) {
            return new DiscountOutcome(Money.zero(grossAmount.currency()), grossAmount, List.of());
        }

        List<Discount> activeDiscounts = eligibleDiscounts.stream()
            .filter(d -> d.isApplicable(grossAmount, evaluationTime))
            .sorted(Comparator.comparingInt(Discount::priority))
            .toList();

        if (activeDiscounts.isEmpty()) {
            return new DiscountOutcome(Money.zero(grossAmount.currency()), grossAmount, List.of());
        }

        // An EXCLUSIVE discount cannot be combined with anything, so the engine selects the single
        // best offer among ALL applicable discounts - not only among the exclusive ones. Selecting
        // among exclusives alone would silently discard a larger non-exclusive saving.
        boolean hasExclusive = activeDiscounts.stream()
            .anyMatch(d -> d.stackingRule() == DiscountStackingRule.EXCLUSIVE);

        if (hasExclusive) {
            return evaluateSingleBest(grossAmount, activeDiscounts);
        }

        // Stacking mode evaluation
        List<TraceStep> trace = new ArrayList<>();
        Money currentBalance = grossAmount;
        Money accumulatedDiscount = Money.zero(grossAmount.currency());

        for (Discount discount : activeDiscounts) {
            Money potentialDiscount = calculateIndividualDiscount(grossAmount, currentBalance, discount);

            // Apply maxCap if present
            if (discount.maxCap().isPresent()) {
                Money cap = discount.maxCap().get();
                if (potentialDiscount.compareTo(cap) > 0) {
                    potentialDiscount = cap;
                    trace.add(TraceStep.of("DISCOUNT_CAPPED", "Discount %s capped at %s".formatted(discount.code(), cap)));
                }
            }

            // Ensure discount does not exceed current remaining balance
            potentialDiscount = potentialDiscount.min(currentBalance);
            accumulatedDiscount = accumulatedDiscount.plus(potentialDiscount);
            currentBalance = currentBalance.minus(potentialDiscount);

            trace.add(TraceStep.of(
                "DISCOUNT_APPLIED",
                "Applied [%s] rule=%s: reduced by %s (remaining: %s)".formatted(
                    discount.code(),
                    discount.stackingRule(),
                    potentialDiscount,
                    currentBalance
                )
            ));

            if (currentBalance.isZero()) {
                break;
            }
        }

        return new DiscountOutcome(accumulatedDiscount, currentBalance, trace);
    }

    private Money calculateIndividualDiscount(Money originalGross, Money currentBalance, Discount discount) {
        return switch (discount.type()) {
            case PERCENTAGE -> {
                BigDecimal factor = discount.value().divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN);
                if (discount.stackingRule() == DiscountStackingRule.ADDITIVE) {
                    // Additive applies directly to original gross
                    yield originalGross.times(factor);
                } else {
                    // Waterfall and compound both apply to the running remaining balance; they are
                    // two historical names for the same sequential rule (see DiscountStackingRule).
                    yield currentBalance.times(factor);
                }
            }
            case FIXED_AMOUNT -> {
                // The discount carries the currency it was created in. Reinterpreting a USD coupon
                // as EUR 20 because the invoice happens to be in EUR is a silent revenue error, so
                // a mismatch is refused instead.
                CurrencyUnit discountCurrency = discount.fixedCurrency().orElse(originalGross.currency());
                if (!discountCurrency.equals(originalGross.currency())) {
                    throw new IllegalArgumentException(
                        "Discount %s is in %s but is applied to a %s amount; convert it first".formatted(
                            discount.code(), discountCurrency.code(), originalGross.currency().code()));
                }
                yield Money.of(discount.value(), discountCurrency);
            }
            // Priced by DefaultPricingEngine from the quantity it removes; there is no monetary
            // value on the discount itself.
            case FREE_UNITS -> Money.zero(originalGross.currency());
        };
    }

    /**
     * Applies exactly one discount: the one that saves the customer the most.
     *
     * <p>Used when an EXCLUSIVE discount is present. Competitors include non-exclusive discounts,
     * because "exclusive" means "nothing stacks with it", not "other offers are void".
     */
    private DiscountOutcome evaluateSingleBest(Money grossAmount, List<Discount> activeDiscounts) {
        List<TraceStep> trace = new ArrayList<>();
        Discount bestDiscount = null;
        Money maxSaving = Money.zero(grossAmount.currency());

        for (Discount d : activeDiscounts) {
            Money saving = calculateIndividualDiscount(grossAmount, grossAmount, d);
            if (d.maxCap().isPresent() && saving.compareTo(d.maxCap().get()) > 0) {
                saving = d.maxCap().get();
            }
            saving = saving.min(grossAmount);

            if (saving.compareTo(maxSaving) > 0) {
                maxSaving = saving;
                bestDiscount = d;
            }
        }

        if (bestDiscount != null) {
            Money net = grossAmount.minus(maxSaving);
            trace.add(TraceStep.of(
                "EXCLUSIVE_DISCOUNT_CHOSEN",
                "Best offer selected: %s saving %s".formatted(bestDiscount.code(), maxSaving)
            ));
            return new DiscountOutcome(maxSaving, net, trace);
        }

        return new DiscountOutcome(Money.zero(grossAmount.currency()), grossAmount, trace);
    }
}
