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
 * High-precision proportional allocation using the Largest-Remainder (Hamilton-Hare) method.
 * Eliminates financial penny-rounding discrepancies across multi-line item distributions.
 */
public final class RemainderAllocator {

    private record AllocationCandidate(
        int index,
        BigDecimal rawExact,
        BigDecimal truncated,
        BigDecimal fractionalRemainder
    ) {}

    public static List<Money> allocate(Money totalAmount, List<BigDecimal> weights) {
        Objects.requireNonNull(totalAmount, "totalAmount cannot be null");
        Objects.requireNonNull(weights, "weights cannot be null");

        if (weights.isEmpty()) {
            return List.of();
        }

        CurrencyUnit currency = totalAmount.currency();
        int fractionDigits = currency.defaultFractionDigits();
        BigDecimal smallestUnit = BigDecimal.ONE.movePointLeft(fractionDigits);

        BigDecimal totalWeight = weights.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0 || totalAmount.isZero()) {
            List<Money> zeros = new ArrayList<>(weights.size());
            for (int i = 0; i < weights.size(); i++) {
                zeros.add(Money.zero(currency));
            }
            return zeros;
        }

        List<AllocationCandidate> candidates = new ArrayList<>(weights.size());
        BigDecimal sumTruncated = BigDecimal.ZERO;

        for (int i = 0; i < weights.size(); i++) {
            BigDecimal weight = weights.get(i);
            BigDecimal shareRatio = weight.divide(totalWeight, 12, RoundingMode.HALF_EVEN);
            BigDecimal rawShare = totalAmount.amount().multiply(shareRatio);
            BigDecimal truncated = rawShare.setScale(fractionDigits, RoundingMode.DOWN);
            BigDecimal remainder = rawShare.subtract(truncated);

            candidates.add(new AllocationCandidate(i, rawShare, truncated, remainder));
            sumTruncated = sumTruncated.add(truncated);
        }

        BigDecimal difference = totalAmount.amount().setScale(fractionDigits, RoundingMode.HALF_EVEN)
            .subtract(sumTruncated);

        int centsToDistribute = difference.movePointRight(fractionDigits).intValue();

        // Sort descending by fractional remainder
        List<AllocationCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(AllocationCandidate::fractionalRemainder).reversed());

        BigDecimal[] finalShares = new BigDecimal[weights.size()];
        for (AllocationCandidate c : candidates) {
            finalShares[c.index()] = c.truncated();
        }

        // Distribute remainder cents to highest fractional remainders
        for (int i = 0; i < centsToDistribute && i < sorted.size(); i++) {
            int targetIdx = sorted.get(i).index();
            finalShares[targetIdx] = finalShares[targetIdx].add(smallestUnit);
        }

        List<Money> result = new ArrayList<>(weights.size());
        for (BigDecimal share : finalShares) {
            result.add(Money.of(share, currency));
        }

        return result;
    }
}
