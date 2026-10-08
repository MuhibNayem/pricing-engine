package com.saas.pricing.metering.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SPI for persisting raw meter events and querying historical usage events.
 */
public interface MeterEventRepository {

    /**
     * Persists a meter event. Returns true if saved, false if already exists (duplicate).
     */
    boolean saveEvent(MeterEvent event);

    /**
     * Finds events for a specific meter within a time interval [from, to).
     */
    List<MeterEvent> findEvents(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, Instant from, Instant to);

    /**
     * Finds all events across all meters for a tenant/customer in the given time interval.
     */
    List<MeterEvent> findAllEvents(TenantId tenantId, Optional<CustomerId> customerId, Instant from, Instant to);
}
