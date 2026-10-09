package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.metering.spi.MeterAggregationRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory cache/repository of calculated MeterAggregations.
 */
public class InMemoryMeterAggregationRepository implements MeterAggregationRepository, ResettableForTesting {

    private final ConcurrentMap<String, MeterAggregation> aggregations = new ConcurrentHashMap<>();

    private String buildKey(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window) {
        return "%s::%s::%s::%s::%s".formatted(
            tenantId.value(),
            customerId.map(CustomerId::value).orElse("*"),
            meterCode.toUpperCase(),
            window.startTime().toString(),
            window.endTime().toString()
        );
    }

    @Override
    public Optional<MeterAggregation> findAggregation(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        return Optional.ofNullable(aggregations.get(buildKey(tenantId, customerId, meterCode, window)));
    }

    @Override
    public void saveAggregation(MeterAggregation aggregation) {
        Objects.requireNonNull(aggregation, "aggregation cannot be null");
        aggregations.put(
            buildKey(aggregation.tenantId(), aggregation.customerId(), aggregation.meterCode(), aggregation.window()),
            aggregation
        );
    }

    @Override
    public void invalidate(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        aggregations.remove(buildKey(tenantId, customerId, meterCode, window));
    }

    @Override
    public void invalidateForEvent(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, java.time.Instant eventTimestamp) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(eventTimestamp, "eventTimestamp cannot be null");

        aggregations.values().removeIf(a ->
            a.tenantId().equals(tenantId) &&
            a.meterCode().equalsIgnoreCase(meterCode) &&
            (customerId.isEmpty() || a.customerId().isEmpty() || a.customerId().equals(customerId)) &&
            a.window().contains(eventTimestamp)
        );
    }

    @Override
    public List<MeterAggregation> findAllAggregations(TenantId tenantId, Optional<CustomerId> customerId, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        return aggregations.values().stream()
            .filter(a -> a.tenantId().equals(tenantId))
            .filter(a -> customerId.isEmpty() || a.customerId().equals(customerId))
            .filter(a -> a.window().equals(window))
            .toList();
    }

    @Override
    public void resetForTesting() {
        aggregations.clear();
    }
}
