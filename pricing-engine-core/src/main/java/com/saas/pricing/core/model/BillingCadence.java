package com.saas.pricing.core.model;

/**
 * How often a charge recurs.
 *
 * <p>{@link #DAILY} and {@link #WEEKLY} exist because a renewal day only has meaning against a
 * cadence: "the 31st" is a monthly property, "Wednesday" is a weekly one. A cadence set without
 * the matching anchor dimension is rejected by {@link BillingCycleAnchor}.
 */
public enum BillingCadence {
    ONE_TIME,
    DAILY,
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    ANNUAL,
    USAGE_BASED;

    /** True when this cadence renews on a recurring boundary rather than being billed once. */
    public boolean isRecurring() {
        return this != ONE_TIME && this != USAGE_BASED;
    }

    /** Nominal number of months between renewals; {@code 0} for sub-monthly cadences. */
    public int monthsPerPeriod() {
        return switch (this) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case ANNUAL -> 12;
            default -> 0;
        };
    }
}
