package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Encapsulates the time boundaries for high-precision proration calculations.
 */
public record ProrationWindow(
    Instant periodStart,
    Instant periodEnd,
    Instant effectiveStart,
    Instant effectiveEnd
) implements Serializable {

    public static final int RATIO_SCALE = 10;

    public ProrationWindow {
        Objects.requireNonNull(periodStart, "periodStart cannot be null");
        Objects.requireNonNull(periodEnd, "periodEnd cannot be null");
        Objects.requireNonNull(effectiveStart, "effectiveStart cannot be null");
        Objects.requireNonNull(effectiveEnd, "effectiveEnd cannot be null");

        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("periodEnd must be after periodStart");
        }
        if (!effectiveEnd.isAfter(effectiveStart)) {
            throw new IllegalArgumentException("effectiveEnd must be after effectiveStart");
        }
    }

    public static ProrationWindow of(Instant periodStart, Instant periodEnd, Instant effectiveStart, Instant effectiveEnd) {
        return new ProrationWindow(periodStart, periodEnd, effectiveStart, effectiveEnd);
    }

    /**
     * Calculates the exact proration factor: (duration active) / (total period duration).
     * Bounded between 0.0 and 1.0.
     */
    public BigDecimal calculateFactor() {
        Instant activeStart = effectiveStart.isBefore(periodStart) ? periodStart : effectiveStart;
        Instant activeEnd = effectiveEnd.isAfter(periodEnd) ? periodEnd : effectiveEnd;

        if (!activeEnd.isAfter(activeStart)) {
            return BigDecimal.ZERO;
        }

        long activeSeconds = Duration.between(activeStart, activeEnd).toSeconds();
        long totalSeconds = Duration.between(periodStart, periodEnd).toSeconds();

        if (totalSeconds <= 0) {
            return BigDecimal.ONE;
        }

        return BigDecimal.valueOf(activeSeconds)
            .divide(BigDecimal.valueOf(totalSeconds), RATIO_SCALE, RoundingMode.HALF_EVEN)
            .min(BigDecimal.ONE);
    }
}
