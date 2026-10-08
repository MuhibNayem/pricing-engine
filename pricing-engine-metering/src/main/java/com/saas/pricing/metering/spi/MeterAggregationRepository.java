package com.saas.pricing.metering.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.TimeWindow;

import java.util.List;
import java.util.Optional;

/**
 * SPI for caching or persisting materialized meter aggregations for windows.
 */
public interface MeterAggregationRepository {

    /**
     * Retrieves an existing aggregation for a window, if available.
     */
    Optional<MeterAggregation> findAggregation(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window);

    /**
     * Persists or updates an aggregation for a window.
     */
    void saveAggregation(MeterAggregation aggregation);

    /**
     * Invalidates cached aggregation for a window (e.g. when an out-of-order event arrives).
     */
    void invalidate(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window);

    /**
     * Invalidates cached aggregations overlapping the given event's timestamp.
     */
    void invalidateForEvent(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, java.time.Instant eventTimestamp);

    /**
     * Finds all aggregations for a tenant and customer in a specific time window.
     */
    List<MeterAggregation> findAllAggregations(TenantId tenantId, Optional<CustomerId> customerId, TimeWindow window);
}
