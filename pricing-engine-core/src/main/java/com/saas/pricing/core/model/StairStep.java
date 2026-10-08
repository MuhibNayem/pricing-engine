package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines a stair-step (package) price tier.
 * Customers in [lowerBound, upperBound) pay a discrete fixed fee regardless of exact count.
 */
public record StairStep(
    BigDecimal lowerBound,
    Optional<BigDecimal> upperBound,
    Money fee
) implements Serializable {

    public StairStep {
        Objects.requireNonNull(lowerBound, "lowerBound cannot be null");
        Objects.requireNonNull(upperBound, "upperBound cannot be null");
        Objects.requireNonNull(fee, "fee cannot be null");

        if (lowerBound.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("lowerBound cannot be negative");
        }
        upperBound.ifPresent(ub -> {
            if (ub.compareTo(lowerBound) <= 0) {
                throw new IllegalArgumentException("upperBound must be greater than lowerBound");
            }
        });
    }

    public static StairStep of(BigDecimal lowerBound, BigDecimal upperBound, Money fee) {
        return new StairStep(lowerBound, Optional.of(upperBound), fee);
    }

    public static StairStep unbounded(BigDecimal lowerBound, Money fee) {
        return new StairStep(lowerBound, Optional.empty(), fee);
    }

    public boolean contains(BigDecimal quantity) {
        if (quantity.compareTo(lowerBound) < 0) {
            return false;
        }
        return upperBound.map(ub -> quantity.compareTo(ub) < 0).orElse(true);
    }
}
