package com.saas.pricing.metering.model;

import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregated meter reading for a specific time window.
 * Can be directly converted to a {@link BillableItemRequest} for evaluation by the PricingEngine.
 *
 * <p>{@code approximate} is false for exact aggregations. It is set to true when the engine could not
 * compute an exact result for the window — currently only when a {@link AggregationType#DISTINCT_COUNT}
 * meter exceeded the configured distinct-value cardinality cap, in which case {@code aggregatedValue} is
 * a capped <em>undercount</em>. Downstream rating must refuse to bill an undercount rather than charge it.</p>
 */
public record MeterAggregation(
    TenantId tenantId,
    Optional<CustomerId> customerId,
    String meterCode,
    TimeWindow window,
    AggregationType aggregationType,
    BigDecimal aggregatedValue,
    long eventCount,
    Optional<Instant> lastEventTime,
    boolean approximate
) implements Serializable {

    public MeterAggregation {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");
        Objects.requireNonNull(aggregationType, "aggregationType cannot be null");
        Objects.requireNonNull(aggregatedValue, "aggregatedValue cannot be null");
        Objects.requireNonNull(lastEventTime, "lastEventTime cannot be null");

        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount cannot be negative");
        }
    }

    /**
     * Backward-compatible constructor for exact aggregations, retained so that existing adapters
     * constructing the previous 8-component form keep compiling and behave identically.
     */
    public MeterAggregation(
        TenantId tenantId,
        Optional<CustomerId> customerId,
        String meterCode,
        TimeWindow window,
        AggregationType aggregationType,
        BigDecimal aggregatedValue,
        long eventCount,
        Optional<Instant> lastEventTime
    ) {
        this(tenantId, customerId, meterCode, window, aggregationType, aggregatedValue, eventCount, lastEventTime, false);
    }

    public static MeterAggregation empty(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window, AggregationType type) {
        return new MeterAggregation(tenantId, customerId, meterCode, window, type, BigDecimal.ZERO, 0L, Optional.empty(), false);
    }

    public static MeterAggregation empty(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window, AggregationType type, boolean approximate) {
        return new MeterAggregation(tenantId, customerId, meterCode, window, type, BigDecimal.ZERO, 0L, Optional.empty(), approximate);
    }

    /**
     * @return true when {@link #aggregatedValue()} is a capped undercount and must not be billed as-is.
     */
    public boolean isApproximate() {
        return approximate;
    }

    /**
     * Converts this aggregated meter reading into a BillableItemRequest for pricing evaluation.
     */
    public BillableItemRequest toBillableItemRequest() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("windowStart", window.startTime().toString());
        attrs.put("windowEnd", window.endTime().toString());
        attrs.put("aggregationType", aggregationType.name());
        attrs.put("eventCount", eventCount);
        attrs.put("approximate", approximate);
        lastEventTime.ifPresent(t -> attrs.put("lastEventTime", t.toString()));

        return BillableItemRequest.of(meterCode, aggregatedValue, attrs);
    }
}
