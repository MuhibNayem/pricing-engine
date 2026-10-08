package com.saas.pricing.metering.model;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Immutable half-open interval [startTime, endTime) defining an aggregation time window.
 */
public record TimeWindow(
    Instant startTime,
    Instant endTime
) implements Serializable, Comparable<TimeWindow> {

    public TimeWindow {
        Objects.requireNonNull(startTime, "startTime cannot be null");
        Objects.requireNonNull(endTime, "endTime cannot be null");
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime (%s) cannot be before startTime (%s)".formatted(endTime, startTime));
        }
    }

    public static TimeWindow of(Instant startTime, Instant endTime) {
        return new TimeWindow(startTime, endTime);
    }

    public static TimeWindow hourly(Instant timestamp) {
        Instant start = timestamp.truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        return new TimeWindow(start, end);
    }

    public static TimeWindow daily(Instant timestamp) {
        return daily(timestamp, ZoneOffset.UTC);
    }

    public static TimeWindow daily(Instant timestamp, ZoneId zoneId) {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(zoneId, "zoneId cannot be null");
        LocalDate date = timestamp.atZone(zoneId).toLocalDate();
        Instant start = date.atStartOfDay(zoneId).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zoneId).toInstant();
        return new TimeWindow(start, end);
    }

    public static TimeWindow monthly(Instant timestamp) {
        return monthly(timestamp, ZoneOffset.UTC);
    }

    public static TimeWindow monthly(Instant timestamp, ZoneId zoneId) {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(zoneId, "zoneId cannot be null");
        var zonedDateTime = timestamp.atZone(zoneId);
        LocalDate firstDay = zonedDateTime.toLocalDate().withDayOfMonth(1);
        Instant start = firstDay.atStartOfDay(zoneId).toInstant();
        Instant end = firstDay.plusMonths(1).atStartOfDay(zoneId).toInstant();
        return new TimeWindow(start, end);
    }

    public boolean contains(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        return !timestamp.isBefore(startTime) && timestamp.isBefore(endTime);
    }

    public Duration duration() {
        return Duration.between(startTime, endTime);
    }

    @Override
    public int compareTo(TimeWindow other) {
        int cmp = this.startTime.compareTo(other.startTime);
        if (cmp != 0) {
            return cmp;
        }
        return this.endTime.compareTo(other.endTime);
    }
}
