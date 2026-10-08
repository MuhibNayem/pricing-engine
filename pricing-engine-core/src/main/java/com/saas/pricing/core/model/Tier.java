package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines a pricing tier bounded by [lowerBound, upperBound).
 * UpperBound is exclusive (or infinite if Optional.empty()).
 */
public record Tier(
    BigDecimal lowerBound,
    Optional<BigDecimal> upperBound,
    BigDecimal unitPrice,
    BigDecimal flatFee
) implements Serializable {

    public Tier {
        Objects.requireNonNull(lowerBound, "lowerBound cannot be null");
        Objects.requireNonNull(upperBound, "upperBound cannot be null");
        Objects.requireNonNull(unitPrice, "unitPrice cannot be null");
        Objects.requireNonNull(flatFee, "flatFee cannot be null");

        if (lowerBound.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("lowerBound cannot be negative: " + lowerBound);
        }
        upperBound.ifPresent(ub -> {
            if (ub.compareTo(lowerBound) <= 0) {
                throw new IllegalArgumentException("upperBound (%s) must be greater than lowerBound (%s)".formatted(ub, lowerBound));
            }
        });
        if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("unitPrice cannot be negative: " + unitPrice);
        }
        if (flatFee.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("flatFee cannot be negative: " + flatFee);
        }
    }

    public static Tier of(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal unitPrice) {
        return new Tier(lowerBound, Optional.of(upperBound), unitPrice, BigDecimal.ZERO);
    }

    public static Tier of(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal unitPrice, BigDecimal flatFee) {
        return new Tier(lowerBound, Optional.of(upperBound), unitPrice, flatFee);
    }

    public static Tier unbounded(BigDecimal lowerBound, BigDecimal unitPrice) {
        return new Tier(lowerBound, Optional.empty(), unitPrice, BigDecimal.ZERO);
    }

    public static Tier unbounded(BigDecimal lowerBound, BigDecimal unitPrice, BigDecimal flatFee) {
        return new Tier(lowerBound, Optional.empty(), unitPrice, flatFee);
    }

    public boolean contains(BigDecimal quantity) {
        if (quantity.compareTo(lowerBound) < 0) {
            return false;
        }
        return upperBound.map(ub -> quantity.compareTo(ub) < 0).orElse(true);
    }

    /**
     * Calculates the billable units within this specific tier bracket for a given total quantity.
     * Used for graduated (slab) pricing.
     */
    public BigDecimal unitsInTier(BigDecimal totalQuantity) {
        if (totalQuantity.compareTo(lowerBound) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal effectiveUpper = upperBound.map(ub -> totalQuantity.min(ub)).orElse(totalQuantity);
        return effectiveUpper.subtract(lowerBound);
    }
}
