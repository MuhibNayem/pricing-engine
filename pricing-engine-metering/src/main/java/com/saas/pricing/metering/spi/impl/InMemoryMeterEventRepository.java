package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.spi.MeterEventRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory implementation of MeterEventRepository.
 */
public class InMemoryMeterEventRepository implements MeterEventRepository {

    private final List<MeterEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public boolean saveEvent(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        // Check for idempotency / duplicate eventId or idempotencyKey
        boolean exists = events.stream().anyMatch(e ->
            e.tenantId().equals(event.tenantId()) &&
            (e.eventId().equals(event.eventId()) || e.idempotencyKey().equals(event.idempotencyKey()))
        );
        if (exists) {
            return false;
        }
        events.add(event);
        return true;
    }

    @Override
    public List<MeterEvent> findEvents(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");

        return events.stream()
            .filter(e -> e.tenantId().equals(tenantId))
            .filter(e -> customerId.isEmpty() || e.customerId().equals(customerId))
            .filter(e -> e.meterCode().equalsIgnoreCase(meterCode))
            .filter(e -> !e.timestamp().isBefore(from) && e.timestamp().isBefore(to))
            .sorted(Comparator.comparing(MeterEvent::timestamp))
            .toList();
    }

    @Override
    public List<MeterEvent> findAllEvents(TenantId tenantId, Optional<CustomerId> customerId, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");

        return events.stream()
            .filter(e -> e.tenantId().equals(tenantId))
            .filter(e -> customerId.isEmpty() || e.customerId().equals(customerId))
            .filter(e -> !e.timestamp().isBefore(from) && e.timestamp().isBefore(to))
            .sorted(Comparator.comparing(MeterEvent::timestamp))
            .toList();
    }

    public void clear() {
        events.clear();
    }
}
