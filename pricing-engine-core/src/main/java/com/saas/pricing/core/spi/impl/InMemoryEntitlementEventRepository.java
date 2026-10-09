package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.EntitlementEvent;
import com.saas.pricing.core.spi.EntitlementEventRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory append-only entitlement event store.
 *
 * <p>Enforces the same rules as the JDBC adapter so a deployment that forgets to configure JDBC
 * does not silently lose the guarantees that make the projection trustworthy.
 */
public class InMemoryEntitlementEventRepository implements EntitlementEventRepository {

    private static final Comparator<EntitlementEvent> REPLAY_ORDER =
        Comparator.comparing(EntitlementEvent::effectiveAt)
            .thenComparing(EntitlementEvent::recordedAt)
            .thenComparing(EntitlementEvent::eventId);

    private final Map<String, List<EntitlementEvent>> events = new ConcurrentHashMap<>();

    private static String key(TenantId tenantId, CustomerId customerId, String featureKey) {
        return tenantId.value() + "::"
            + customerId.value() + "::" + featureKey;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A redelivered identical event is skipped; the same id with different content is refused,
     * because silently accepting it would make one id mean two different changes.
     */
    @Override
    public void append(List<EntitlementEvent> newEvents) {
        Objects.requireNonNull(newEvents, "events cannot be null");
        for (EntitlementEvent event : newEvents) {
            List<EntitlementEvent> stream = events.computeIfAbsent(
                key(event.tenantId(), event.customerId(), event.featureKey()),
                k -> new ArrayList<>());
            synchronized (stream) {
                var existing = stream.stream()
                    .filter(e -> e.eventId().equals(event.eventId()))
                    .findFirst();
                if (existing.isPresent()) {
                    if (existing.get().equals(event)) {
                        continue;
                    }
                    throw new IllegalArgumentException(
                        "Entitlement event id " + event.eventId()
                            + " already exists with different content; the stream is append-only");
                }
                stream.add(event);
                stream.sort(REPLAY_ORDER);
            }
        }
    }

    @Override
    public List<EntitlementEvent> findEvents(TenantId tenantId, CustomerId customerId,
                                             String featureKey, Optional<Instant> effectiveBefore) {
        List<EntitlementEvent> stream = events.get(key(tenantId, customerId, featureKey));
        if (stream == null) {
            return List.of();
        }
        List<EntitlementEvent> snapshot;
        synchronized (stream) {
            snapshot = List.copyOf(stream);
        }
        return effectiveBefore
            .map(before -> snapshot.stream().filter(e -> !e.effectiveAt().isAfter(before)).toList())
            .orElse(snapshot);
    }

    @Override
    public List<EntitlementEvent> findAllEvents(TenantId tenantId, CustomerId customerId,
                                                Optional<Instant> effectiveBefore) {
        String prefix = tenantId.value() + "::"
            + customerId.value() + "::";
        List<EntitlementEvent> all = new ArrayList<>();
        events.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                synchronized (v) {
                    all.addAll(v);
                }
            }
        });
        all.sort(REPLAY_ORDER);
        return effectiveBefore
            .map(before -> all.stream().filter(e -> !e.effectiveAt().isAfter(before)).toList())
            .orElse(all);
    }

    @Override
    public Optional<EntitlementEvent> findLatest(TenantId tenantId, CustomerId customerId,
                                                String featureKey) {
        List<EntitlementEvent> stream = events.get(key(tenantId, customerId, featureKey));
        if (stream == null) {
            return Optional.empty();
        }
        synchronized (stream) {
            return stream.isEmpty() ? Optional.empty() : Optional.of(stream.getLast());
        }
    }

    public void clear() {
        events.clear();
    }
}