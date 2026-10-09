package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.spi.MeterEventRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory implementation of MeterEventRepository.
 *
 * <p>Deduplication is O(1) amortised via two index maps — {@code (tenant, eventId)} and
 * {@code (tenant, idempotencyKey)} — instead of a linear scan of a {@code CopyOnWriteArrayList},
 * which was O(n) per insert (O(n²) per batch) and additionally copied the whole backing array on
 * every single write.</p>
 *
 * <p>Query results are sorted by {@code (timestamp, eventId)} rather than relying on insertion order,
 * so iteration is deterministic regardless of hash ordering or concurrent insertion. Both index maps
 * hold references to the same {@link MeterEvent} instances, so the extra index costs one additional
 * map entry (not a second copy of the event) per stored event.</p>
 */
public class InMemoryMeterEventRepository implements MeterEventRepository, ResettableForTesting {

    /** Source of truth for query iteration, keyed by (tenant, eventId). */
    private final ConcurrentMap<String, MeterEvent> eventsByEventId = new ConcurrentHashMap<>();

    /** Dedupe index on (tenant, idempotencyKey). */
    private final ConcurrentMap<String, MeterEvent> eventsByIdempotencyKey = new ConcurrentHashMap<>();

    /**
     * Guards the update of both indexes so a rejected duplicate cannot leave a half-inserted entry.
     * Critical section is two hash lookups with no I/O, so contention is negligible compared to the
     * full-array copy the previous implementation paid on every write.
     */
    private final ReentrantLock indexLock = new ReentrantLock();

    private static final Comparator<MeterEvent> EVENT_ORDER =
        Comparator.comparing(MeterEvent::timestamp).thenComparing(MeterEvent::eventId);

    private static String eventKey(TenantId tenantId, String eventId) {
        return tenantId.value() + "::" + eventId;
    }

    private static String idempotencyKey(TenantId tenantId, String idempotencyKey) {
        return tenantId.value() + "::" + idempotencyKey;
    }

    @Override
    public boolean saveEvent(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        indexLock.lock();
        try {
            String evKey = eventKey(event.tenantId(), event.eventId());
            String idemKey = idempotencyKey(event.tenantId(), event.idempotencyKey());

            // Reject on either identity, preserving the previous duplicate semantics exactly.
            if (eventsByEventId.containsKey(evKey) || eventsByIdempotencyKey.containsKey(idemKey)) {
                return false;
            }

            eventsByEventId.put(evKey, event);
            eventsByIdempotencyKey.put(idemKey, event);
            return true;
        } finally {
            indexLock.unlock();
        }
    }

    @Override
    public List<MeterEvent> findEvents(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");

        return eventsByEventId.values().stream()
            .filter(e -> e.tenantId().equals(tenantId))
            .filter(e -> customerId.isEmpty() || e.customerId().equals(customerId))
            .filter(e -> e.meterCode().equalsIgnoreCase(meterCode))
            .filter(e -> !e.timestamp().isBefore(from) && e.timestamp().isBefore(to))
            .sorted(EVENT_ORDER)
            .toList();
    }

    @Override
    public List<MeterEvent> findAllEvents(TenantId tenantId, Optional<CustomerId> customerId, Instant from, Instant to) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(from, "from cannot be null");
        Objects.requireNonNull(to, "to cannot be null");

        return eventsByEventId.values().stream()
            .filter(e -> e.tenantId().equals(tenantId))
            .filter(e -> customerId.isEmpty() || e.customerId().equals(customerId))
            .filter(e -> !e.timestamp().isBefore(from) && e.timestamp().isBefore(to))
            .sorted(EVENT_ORDER)
            .toList();
    }

    @Override
    public void resetForTesting() {
        indexLock.lock();
        try {
            eventsByEventId.clear();
            eventsByIdempotencyKey.clear();
        } finally {
            indexLock.unlock();
        }
    }

    /**
     * @return number of stored events, for test assertions and operational visibility.
     */
    public int size() {
        return eventsByEventId.size();
    }
}