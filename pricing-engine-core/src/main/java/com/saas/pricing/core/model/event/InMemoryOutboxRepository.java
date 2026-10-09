package com.saas.pricing.core.model.event;

import com.saas.pricing.core.model.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory outbox, mirroring the JDBC adapter's duplicate semantics.
 *
 * <p>An identical re-enqueue is a no-op, because the same event id can legitimately be written
 * twice if a transaction is retried; the same id with different content is refused, because that
 * would mean one id means two different things.
 */
public class InMemoryOutboxRepository implements OutboxRepository {

    private final Map<String, OutboxEvent> events = new ConcurrentHashMap<>();

    @Override
    public void enqueue(OutboxEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        var existing = events.putIfAbsent(event.eventId(), event);
        if (existing != null && !existing.equals(event)) {
            throw new IllegalArgumentException(
                "Outbox event " + event.eventId() + " already queued with different content");
        }
    }

    @Override
    public void recordDelivery(OutboxEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        events.put(event.eventId(), event);
    }

    @Override
    public List<OutboxEvent> findDue(Instant at, int limit) {
        Objects.requireNonNull(at, "at cannot be null");
        List<OutboxEvent> due = new ArrayList<>();
        for (OutboxEvent event : events.values()) {
            if (event.isDue(at)) {
                due.add(event);
            }
        }
        due.sort(Comparator.comparing(OutboxEvent::createdAt).thenComparing(OutboxEvent::eventId));
        return bounded(due, limit);
    }

    @Override
    public List<OutboxEvent> findByTenant(TenantId tenantId, int limit) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        List<OutboxEvent> found = new ArrayList<>(events.values().stream()
            .filter(e -> e.tenantId().equals(tenantId.value()))
            .toList());
        found.sort(Comparator.comparing(OutboxEvent::createdAt).thenComparing(OutboxEvent::eventId));
        return bounded(found, limit);
    }

    @Override
    public List<OutboxEvent> findUndelivered(String topic, int limit) {
        List<OutboxEvent> undelivered = new ArrayList<>(events.values().stream()
            .filter(e -> !e.isDelivered() && (topic == null || topic.isBlank() || e.topic().equals(topic)))
            .toList());
        undelivered.sort(Comparator.comparing(OutboxEvent::createdAt));
        return bounded(undelivered, limit);
    }

    private static List<OutboxEvent> bounded(List<OutboxEvent> source, int limit) {
        int effective = limit <= 0 ? 100 : Math.min(limit, 1_000);
        return source.size() <= effective ? List.copyOf(source) : List.copyOf(source.subList(0, effective));
    }

    public void clear() {
        events.clear();
    }
}