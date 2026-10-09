package com.saas.pricing.core.model;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A single billing period, half-open {@code [start, end)}.
 *
 * <p>Half-open matters: an event exactly at a renewal boundary belongs to the period that is
 * starting, not the one that ended. A closed interval would double-count or drop that instant.
 *
 * @param start   inclusive period start (a renewal boundary)
 * @param end     exclusive period end (the next renewal boundary)
 * @param cadence the cadence this period was derived from
 */
public record BillingPeriod(Instant start, Instant end, BillingCadence cadence) implements Serializable {

    public BillingPeriod {
        Objects.requireNonNull(start, "start cannot be null");
        Objects.requireNonNull(end, "end cannot be null");
        Objects.requireNonNull(cadence, "cadence cannot be null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Billing period end must be after start");
        }
    }

    /** True when {@code instant} falls in {@code [start, end)}. */
    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant cannot be null");
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    /**
     * Exact duration in seconds.
     *
     * <p>Seconds, not a calendar-unit count: a "month" is not a fixed number of seconds, and
     * proration that assumes it is will drift by hours across a year.
     */
    public long durationSeconds() {
        return Duration.between(start, end).toSeconds();
    }

    /**
     * Proration window covering the portion of this period that a charge is effective for.
     *
     * <p>Clamps the effective span to the period, so a change that predates the period (or extends
     * past it) contributes only the overlapping portion.
     */
    public ProrationWindow prorationWindow(Instant effectiveStart, Instant effectiveEnd) {
        Instant clampedStart = effectiveStart.isBefore(start) ? start : effectiveStart;
        Instant clampedEnd = effectiveEnd.isAfter(end) ? end : effectiveEnd;
        if (!clampedEnd.isAfter(clampedStart)) {
            throw new IllegalArgumentException(
                "Effective span " + effectiveStart + ".." + effectiveEnd
                    + " does not overlap billing period " + start + ".." + end);
        }
        return ProrationWindow.of(start, end, clampedStart, clampedEnd);
    }
}