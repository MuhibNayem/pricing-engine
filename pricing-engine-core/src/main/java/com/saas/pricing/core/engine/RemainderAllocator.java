package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Proportional allocation using the Largest-Remainder (Hamilton-Hare) method.
 *
 * <p><strong>Conservation guarantee.</strong> For every accepted input the returned shares sum
 * exactly to {@code totalAmount}, in the currency's minor unit. This holds for negative totals
 * (credit notes, refunds, reversals), for totals whose minor-unit precision is finer than the
 * number of recipients, and for any recipient count &gt; 1.
 *
 * <p><strong>Why integer arithmetic.</strong> The algorithm works entirely in the currency's minor
 * unit (e.g. cents). Each share is {@code floor(totalUnits * weight_i / totalWeight)} plus at most
 * one unit, and the leftover units are handed to the largest remainders. Working in integers makes
 * the conservation property exact rather than dependent on a decimal division precision — the
 * previous implementation truncated with {@link RoundingMode#DOWN} (toward zero), which produces
 * negative remainders for negative totals and silently drops the remainder.
 *
 * <p><strong>Determinism.</strong> Ties on the fractional remainder are broken by ascending
 * recipient index, so the same inputs always yield the same allocation. Auditors rely on this.
 *
 * <p>This class is stateless and thread-safe.
 */
public final class RemainderAllocator {

    private RemainderAllocator() {
        // static utility
    }

    /**
     * Distributes {@code totalAmount} across {@code weights} in proportion to each weight.
     *
     * @param totalAmount the amount to distribute; must be representable exactly in the currency's
     *                    minor unit
     * @param weights     non-negative, strictly increasing or equal weights; one per recipient.
     *                    Must not be empty. If all weights are zero the amount is split evenly.
     * @return one {@link Money} per weight, summing exactly to {@code totalAmount}
     * @throws IllegalArgumentException if the weights are empty or negative, if the weights sum to a
     *                                  non-positive value, or if {@code totalAmount} carries more
     *                                  precision than the currency can represent
     */
    public static List<Money> allocate(Money totalAmount, List<BigDecimal> weights) {
        Objects.requireNonNull(totalAmount, "totalAmount cannot be null");
        Objects.requireNonNull(weights, "weights cannot be null");

        if (weights.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot allocate " + totalAmount + " across zero recipients; the amount would be lost");
        }
        for (int i = 0; i < weights.size(); i++) {
            if (Objects.requireNonNull(weights.get(i), "weights[" + i + "] cannot be null")
                    .compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "weights[" + i + "] is negative (" + weights.get(i) + "); allocation weights must be non-negative");
            }
        }

        CurrencyUnit currency = totalAmount.currency();
        int fractionDigits = currency.defaultFractionDigits();

        BigDecimal totalUnits = toMinorUnits(totalAmount.amount(), currency);

        BigDecimal totalWeight = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean degenerateWeights = totalWeight.compareTo(BigDecimal.ZERO) == 0;
        BigDecimal divisor = degenerateWeights ? BigDecimal.valueOf(weights.size()) : totalWeight;

        int recipients = weights.size();
        BigDecimal[] units = new BigDecimal[recipients];
        BigDecimal[] remainders = new BigDecimal[recipients];
        BigDecimal sumQuotients = BigDecimal.ZERO;

        for (int i = 0; i < recipients; i++) {
            // A degenerate all-zero weight vector carries no proportional signal, so fall back to
            // unit weights (an even split) rather than silently discarding the amount.
            BigDecimal weight = degenerateWeights ? BigDecimal.ONE : weights.get(i);
            BigDecimal numerator = totalUnits.multiply(weight);

            // FLOOR (not DOWN): keeps the remainder in [0, divisor) for negative totals too.
            BigDecimal quotient = numerator.divide(divisor, 0, RoundingMode.FLOOR);
            BigDecimal remainder = numerator.subtract(quotient.multiply(divisor));

            units[i] = quotient;
            remainders[i] = remainder;
            sumQuotients = sumQuotients.add(units[i]);
        }

        // The undistributed units. Always in [0, recipients) because each remainder is < totalWeight.
        BigDecimal leftoverUnits = totalUnits.subtract(sumQuotients);

        List<Integer> order = new ArrayList<>(recipients);
        for (int i = 0; i < recipients; i++) {
            order.add(i);
        }
        // Largest remainder first; exact ties go to the lowest index so allocation is reproducible.
        order.sort(Comparator.comparing((Integer i) -> remainders[i])
                .reversed()
                .thenComparing(Comparator.naturalOrder()));

        BigDecimal one = BigDecimal.ONE;
        for (int k = 0; k < recipients; k++) {
            BigDecimal share = units[order.get(k)];
            if (BigDecimal.valueOf(k).compareTo(leftoverUnits) < 0) {
                share = share.add(one);
            }
            units[order.get(k)] = share;
        }

        BigDecimal smallestUnit = BigDecimal.ONE.movePointLeft(fractionDigits);
        List<Money> result = new ArrayList<>(recipients);
        for (int i = 0; i < recipients; i++) {
            result.add(Money.of(units[i].multiply(smallestUnit), currency));
        }
        return result;
    }

    /**
     * Converts a decimal amount to an exact integer count of the currency's minor unit.
     *
     * @throws IllegalArgumentException if the amount carries precision the currency cannot represent,
     *                                  because silently rounding it would lose money
     */
    private static BigDecimal toMinorUnits(BigDecimal amount, CurrencyUnit currency) {
        BigDecimal scaled = amount.movePointRight(currency.defaultFractionDigits());
        try {
            return scaled.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " carries more precision than " + currency.code()
                            + " can represent (max " + currency.defaultFractionDigits()
                            + " decimal places); rounding it would lose money", e);
        }
    }
}