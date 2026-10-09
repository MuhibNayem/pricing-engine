package com.saas.pricing.core.model;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The renewal point of a billing cycle: the day of month, day of week, or month of year on which a
 * period begins.
 *
 * <p>This is the missing concept behind anniversary versus calendar billing. Without it an engine
 * cannot express "bills on the 31st, clamped to the end of short months", which in turn blocks
 * short-month and leap-year handling, mid-cycle proration, and any comparison of a flat fee's
 * cadence against the period actually being charged.
 *
 * <p><strong>Anchors are UTC.</strong> A billing boundary that depended on the JVM default zone
 * would move twice a year and silently re-price an account.
 *
 * <h2>Short-month and leap-year clamping</h2>
 * A day-of-month anchor is clamped to the length of the month it lands in. An anchor of
 * {@code dayOfMonth(31)} therefore renews:
 * <pre>
 *   Jan 31 -> Feb 28 (Feb 29 in a leap year) -> Mar 31 -> Apr 30 -> May 31
 * </pre>
 * and never skips a renewal, which is what a naive "add one month" implementation gets wrong by
 * either repeating or skipping a boundary.
 *
 * <h2>End-of-month anchors</h2>
 * {@link #endOfMonth()} is distinct from "the 31st" only in intent, not in behaviour: both clamp,
 * and both renew on the last day of a 30-day month. It exists so a contract can state its intent.
 */
public record BillingCycleAnchor(
    Integer dayOfMonth,
    Integer dayOfWeek,
    Integer monthOfYear
) implements Serializable {

    public BillingCycleAnchor {
        if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) {
            throw new IllegalArgumentException("dayOfMonth must be between 1 and 31, got " + dayOfMonth);
        }
        if (dayOfWeek != null && (dayOfWeek < 1 || dayOfWeek > 7)) {
            throw new IllegalArgumentException("dayOfWeek must be an ISO day (1=Monday..7=Sunday), got " + dayOfWeek);
        }
        if (monthOfYear != null && (monthOfYear < 1 || monthOfYear > 12)) {
            throw new IllegalArgumentException("monthOfYear must be between 1 and 12, got " + monthOfYear);
        }
        if (dayOfMonth == null && dayOfWeek == null && monthOfYear == null) {
            throw new IllegalArgumentException("A billing cycle anchor must define at least one component");
        }
    }

    /** Renews on {@code day} of every month, clamped to short months. */
    public static BillingCycleAnchor dayOfMonth(int day) {
        return new BillingCycleAnchor(day, null, null);
    }

    /** Renews on the last day of every month. */
    public static BillingCycleAnchor endOfMonth() {
        return new BillingCycleAnchor(31, null, null);
    }

    /** Renews weekly on {@code day}. */
    public static BillingCycleAnchor dayOfWeek(DayOfWeek day) {
        return new BillingCycleAnchor(null, day.getValue(), null);
    }

    /** Renews annually in {@code month}. */
    public static BillingCycleAnchor monthOfYear(int month, int dayOfMonth) {
        return new BillingCycleAnchor(dayOfMonth, null, month);
    }

    /**
     * The most recent renewal boundary at or before {@code instant}, in UTC.
     *
     * <p>Returns the boundary itself when {@code instant} falls exactly on one, so
     * {@code periodContaining} is half-open {@code [start, end)} and a charge at the exact
     * boundary belongs to the new period, not the old one.
     */
    public Instant boundaryAtOrBefore(Instant instant, BillingCadence cadence) {
        Objects.requireNonNull(instant, "instant cannot be null");
        Objects.requireNonNull(cadence, "cadence cannot be null");
        return switch (cadence) {
            case DAILY, WEEKLY -> weeklyOrDaily(instant, cadence);
            case MONTHLY, QUARTERLY -> monthlyAnchor(instant, cadence);
            case ANNUAL -> annualAnchor(instant);
            case ONE_TIME, USAGE_BASED -> instant;
        };
    }

    /** The first renewal boundary strictly after {@code boundary}. */
    public Instant nextBoundaryAfter(Instant boundary, BillingCadence cadence) {
        Objects.requireNonNull(boundary, "boundary cannot be null");
        Objects.requireNonNull(cadence, "cadence cannot be null");
        ZonedDateTime b = boundary.atZone(ZoneOffset.UTC);
        return switch (cadence) {
            case DAILY -> b.plusDays(1).toInstant();
            case WEEKLY -> b.plusWeeks(1).toInstant();
            // Must land on the ANCHORED day of the later month, clamped - not on the 1st.
            case MONTHLY -> anchoredDayInMonthsFrom(b.toLocalDate(), 1).toInstant();
            case QUARTERLY -> anchoredDayInMonthsFrom(b.toLocalDate(), 3).toInstant();
            case ANNUAL -> anchoredDayInMonthsFrom(b.toLocalDate(), 12).toInstant();
            case ONE_TIME, USAGE_BASED -> boundary;
        };
    }

    /** The period {@code [start, end)} that contains {@code instant}. */
    public BillingPeriod periodContaining(Instant instant, BillingCadence cadence) {
        Instant start = boundaryAtOrBefore(instant, cadence);
        Instant end = nextBoundaryAfter(start, cadence);
        return new BillingPeriod(start, end, cadence);
    }

    // ---------------------------------------------------------------- internals

    private Instant weeklyOrDaily(Instant instant, BillingCadence cadence) {
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        if (cadence == BillingCadence.DAILY) {
            return z.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        int isoTarget = dayOfWeek != null ? dayOfWeek : z.getDayOfWeek().getValue();
        LocalDate date = z.toLocalDate();
        int back = (date.getDayOfWeek().getValue() - isoTarget + 7) % 7;
        return date.minusDays(back).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant monthlyAnchor(Instant instant, BillingCadence cadence) {
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        int step = cadence == BillingCadence.QUARTERLY ? 3 : 1;
        int anchorDay = dayOfMonth != null ? dayOfMonth : z.getDayOfMonth();

        LocalDate candidate = LocalDate.of(z.getYear(), z.getMonthValue(), 1)
                .withDayOfMonth(clampedDay(z.getYear(), z.getMonthValue(), anchorDay));

        if (step > 1) {
            // For quarterly, snap back to the start of the containing quarter-aligned month.
            LocalDate monthStart = LocalDate.of(z.getYear(), z.getMonthValue(), 1);
            LocalDate aligned = monthStart.minusMonths((monthStart.getMonthValue() - 1) % step);
            LocalDate alignedBoundary = aligned.withDayOfMonth(clampedDay(aligned.getYear(), aligned.getMonthValue(), anchorDay));
            if (!z.toLocalDate().isBefore(alignedBoundary)) {
                return alignedBoundary.atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            LocalDate prevAligned = aligned.minusMonths(step);
            return prevAligned.withDayOfMonth(clampedDay(prevAligned.getYear(), prevAligned.getMonthValue(), anchorDay))
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        if (!z.toLocalDate().isBefore(candidate)) {
            return candidate.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        LocalDate previous = startOfMonthPlus(z.toLocalDate(), -1);
        return previous.withDayOfMonth(clampedDay(previous.getYear(), previous.getMonthValue(), anchorDay))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant annualAnchor(Instant instant) {
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        int month = monthOfYear != null ? monthOfYear : z.getMonthValue();
        int day = dayOfMonth != null ? dayOfMonth : z.getDayOfMonth();

        LocalDate thisYear = LocalDate.of(z.getYear(), month, 1)
                .withDayOfMonth(clampedDay(z.getYear(), month, day));
        if (!z.toLocalDate().isBefore(thisYear)) {
            return thisYear.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        LocalDate lastYear = LocalDate.of(z.getYear() - 1, month, 1)
                .withDayOfMonth(clampedDay(z.getYear() - 1, month, day));
        return lastYear.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private int clampedDay(int year, int month) {
        return clampedDay(year, month, dayOfMonth != null ? dayOfMonth : 1);
    }

    /** Clamps a requested day-of-month to the actual length of the given month. */
    private static int clampedDay(int year, int month, int requestedDay) {
        return Math.min(requestedDay, LocalDate.of(year, month, 1).lengthOfMonth());
    }

    /**
     * The anchored day of the month {@code months} after {@code from}, clamped to that month's
     * length. This is what makes a Jan-31 anchor renew on Feb 28 (or Feb 29 in a leap year) and then
     * on Mar 31 again, rather than drifting or skipping a renewal.
     */
    private ZonedDateTime anchoredDayInMonthsFrom(LocalDate from, int months) {
        LocalDate firstOfTarget = startOfMonthPlus(from, months);
        int anchorDay = dayOfMonth != null
            ? dayOfMonth
            : (monthOfYear != null ? firstOfTarget.lengthOfMonth() : from.getDayOfMonth());
        return firstOfTarget.withDayOfMonth(clampedDay(firstOfTarget.getYear(), firstOfTarget.getMonthValue(), anchorDay))
                .atStartOfDay(ZoneOffset.UTC);
    }

    /** First day of the month {@code months} away; day-of-month clamping is applied afterwards. */
    private static LocalDate startOfMonthPlus(LocalDate date, int months) {
        return date.withDayOfMonth(1).plusMonths(months);
    }

    /** Convenience for the common "anniversary" case: renew on the day the contract started. */
    public static BillingCycleAnchor anniversaryOf(Instant contractStart) {
        ZonedDateTime z = contractStart.atZone(ZoneOffset.UTC);
        return dayOfMonth(z.getDayOfMonth());
    }

    public Optional<Integer> dayOfMonthValue() {
        return Optional.ofNullable(dayOfMonth);
    }

    public Optional<Integer> dayOfWeekValue() {
        return Optional.ofNullable(dayOfWeek);
    }

    public Optional<Integer> monthOfYearValue() {
        return Optional.ofNullable(monthOfYear);
    }
}