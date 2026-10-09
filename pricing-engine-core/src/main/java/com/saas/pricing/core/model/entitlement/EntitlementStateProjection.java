package com.saas.pricing.core.model.entitlement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives current entitlement state by replaying an event stream.
 *
 * <p>This is the read side that makes {@link EntitlementEvent} worth having: current state is a
 * <em>function of history</em>, not a column someone remembers to update. That gives reconciliation
 * for free - hand the same events to two replays and any divergence is a real defect rather than a
 * plausible story.
 *
 * <p>Ordering is by {@code (effectiveAt, recordedAt, eventId)} - valid time first, then system
 * time. An event recorded later but effective earlier therefore still applies at the right moment,
 * which is what makes a backdated grant behave correctly.
 *
 * <p>Not thread-safe: derive once per request, or hold per customer.
 */
public final class EntitlementStateProjection {

    private EntitlementStateProjection() {
        // static utility
    }

    /** Every entitlement a customer holds at {@code at}, keyed by feature. */
    public static Map<String, EntitlementEvent.EntitlementState> project(
        List<EntitlementEvent> events, java.time.Instant at) {

        List<EntitlementEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator
            .comparing(EntitlementEvent::effectiveAt)
            .thenComparing(EntitlementEvent::recordedAt)
            .thenComparing(EntitlementEvent::eventId));

        Map<String, EntitlementEvent.EntitlementState> state = new LinkedHashMap<>();
        for (EntitlementEvent event : ordered) {
            if (event.effectiveAt().isAfter(at)) {
                break;
            }
            switch (event.type()) {
                case GRANTED -> state.put(event.featureKey(),
                    new EntitlementEvent.EntitlementState(true, event.featureType(),
                        event.quotaLimit(), Optional.of(BigDecimal.ZERO), event.effectiveAt()));
                case QUOTA_CHANGED -> state.computeIfPresent(event.featureKey(),
                    (key, previous) -> new EntitlementEvent.EntitlementState(
                        previous.active(), event.featureType(), event.quotaLimit(),
                        previous.currentUsage(), previous.effectiveFrom()));
                case REVOKED -> state.remove(event.featureKey());
                default -> throw new IllegalStateException("Unhandled entitlement change: " + event.type());
            }
        }
        return state;
    }

    /** The state of one feature at {@code at}, or empty if the customer does not hold it. */
    public static Optional<EntitlementEvent.EntitlementState> projectOne(
        List<EntitlementEvent> events, String featureKey, java.time.Instant at) {
        return Optional.ofNullable(project(events, at).get(featureKey));
    }

    /**
     * Compares a caller's believed state against the derived one.
     *
     * <p>A drift is a customer being told they hold something they do not, or being denied
     * something they do - both of which surface to the customer as a billing or access complaint,
     * so it is worth an explicit signal rather than a silent mismatch.
     */
    public static Set<String> driftedFeatures(
        Map<String, EntitlementEvent.EntitlementState> derived, Map<String, Boolean> believed) {

        Set<String> drift = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, EntitlementEvent.EntitlementState> entry : derived.entrySet()) {
            boolean believedActive = Boolean.TRUE.equals(believed.get(entry.getKey()));
            if (entry.getValue().active() != believedActive) {
                drift.add(entry.getKey());
            }
        }
        for (String featureKey : believed.keySet()) {
            if (!derived.containsKey(featureKey)) {
                drift.add(featureKey);
            }
        }
        return drift;
    }

    /** Feature keys currently granted. */
    public static Set<String> activeFeatures(Map<String, EntitlementEvent.EntitlementState> state) {
        return state.entrySet().stream()
            .filter(e -> e.getValue().active())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}