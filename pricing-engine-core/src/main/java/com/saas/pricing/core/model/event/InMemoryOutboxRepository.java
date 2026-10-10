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
    private final TraceContextProvider traceContexts;

    public InMemoryOutboxRepository() {
        this(TraceContextProvider.NONE);
    }

    public InMemoryOutboxRepository(TraceContextProvider traceContexts) {
        this.traceContexts = Objects.requireNonNull(traceContexts, "traceContexts cannot be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stamps the current trace context onto the event, inside the caller's transaction, so the
     * trace that caused the state change survives the hop to whoever delivers the event. Mirrors
     * {@code JdbcOutboxRepository} exactly: same seam, same asymmetry.</p>
     */
    @Override
    public void enqueue(OutboxEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        var stamped = event.withTraceContext(traceContexts.current());
        var existing = events.putIfAbsent(stamped.eventId(), stamped);
        if (existing != null && !existing.hasSameContentAs(stamped)) {
            // hasSameContentAs, not equals: a retried transaction re-writing the same id may carry
            // a different trace, or none, and that is the same event rather than a conflict.
            throw new IllegalArgumentException(
                "Outbox event " + stamped.eventId() + " already queued with different content");
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