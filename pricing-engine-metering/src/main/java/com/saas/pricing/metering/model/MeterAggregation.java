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
 */
public record MeterAggregation(
    TenantId tenantId,
    Optional<CustomerId> customerId,
    String meterCode,
    TimeWindow window,
    AggregationType aggregationType,
    BigDecimal aggregatedValue,
    long eventCount,
    Optional<Instant> lastEventTime
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

    public static MeterAggregation empty(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window, AggregationType type) {
        return new MeterAggregation(tenantId, customerId, meterCode, window, type, BigDecimal.ZERO, 0L, Optional.empty());
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
        lastEventTime.ifPresent(t -> attrs.put("lastEventTime", t.toString()));

        return BillableItemRequest.of(meterCode, aggregatedValue, attrs);
    }
}
