package com.saas.pricing.metering.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata definition specifying how events for a given meter code should be aggregated.
 */
public record MeterDefinition(
    String meterCode,
    AggregationType aggregationType,
    Optional<String> distinctProperty,
    String description
) implements Serializable {

    public MeterDefinition {
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(aggregationType, "aggregationType cannot be null");
        Objects.requireNonNull(distinctProperty, "distinctProperty cannot be null");
        Objects.requireNonNull(description, "description cannot be null");

        if (meterCode.isBlank()) {
            throw new IllegalArgumentException("meterCode cannot be blank");
        }
        if (aggregationType == AggregationType.DISTINCT_COUNT && distinctProperty.isEmpty()) {
            throw new IllegalArgumentException("distinctProperty must be specified when aggregationType is DISTINCT_COUNT");
        }
    }

    public static MeterDefinition sum(String meterCode, String description) {
        return new MeterDefinition(meterCode, AggregationType.SUM, Optional.empty(), description);
    }

    public static MeterDefinition count(String meterCode, String description) {
        return new MeterDefinition(meterCode, AggregationType.COUNT, Optional.empty(), description);
    }

    public static MeterDefinition max(String meterCode, String description) {
        return new MeterDefinition(meterCode, AggregationType.MAX, Optional.empty(), description);
    }

    public static MeterDefinition last(String meterCode, String description) {
        return new MeterDefinition(meterCode, AggregationType.LAST, Optional.empty(), description);
    }

    public static MeterDefinition distinctCount(String meterCode, String propertyKey, String description) {
        return new MeterDefinition(meterCode, AggregationType.DISTINCT_COUNT, Optional.of(propertyKey), description);
    }
}
