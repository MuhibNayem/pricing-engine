package com.saas.pricing.starter;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.model.entitlement.EntitlementEvent;
import com.saas.pricing.core.model.entitlement.FeatureType;
import com.saas.pricing.core.model.event.DomainEventFactory;
import com.saas.pricing.core.model.event.OutboxRepository;
import com.saas.pricing.core.spi.EntitlementEventRepository;
import com.saas.pricing.core.spi.EntitlementRepository;

import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Entitlement grants and revocations: the only supported way to change what a customer holds.
 *
 * <p>A change writes three things, in this order and through one collaborator:
 * <ol>
 *   <li>the append-only {@link EntitlementEvent} stream, which is the record of truth;</li>
 *   <li>the legacy mutable {@link CustomerEntitlement} row, kept so existing readers keep working
 *       and so {@link EntitlementReconciler} has something to compare against;</li>
 *   <li>an outbox event, so subscribers are told.</li>
 * </ol>
 *
 * <p>Doing this in one place is the point. Split across callers, a change to the legacy row without
 * an event would be invisible to the projection, and a change without an outbox event would commit
 * an access change that no downstream system ever learns about — a customer either keeps access they
 * paid for, or loses it with no explanation.
 */
public class EntitlementLifecycleService {

    private final EntitlementRepository entitlementRepository;
    private final EntitlementEventRepository eventRepository;
    private final OutboxRepository outboxRepository;
    private final Clock clock;

    public EntitlementLifecycleService(EntitlementRepository entitlementRepository,
                                       EntitlementEventRepository eventRepository,
                                       OutboxRepository outboxRepository,
                                       Clock clock) {
        this.entitlementRepository = Objects.requireNonNull(entitlementRepository, "entitlementRepository cannot be null");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository cannot be null");
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /** Grants an entitlement, recording the event and announcing it. */
    @Transactional
    public EntitlementEvent grant(String eventId, TenantId tenantId, CustomerId customerId,
                                  String featureKey, FeatureType featureType,
                                  Optional<java.math.BigDecimal> quotaLimit, String reason) {
        Instant at = clock.instant();
        EntitlementEvent event = EntitlementEvent.grant(
            eventId, tenantId, customerId, featureKey, featureType, quotaLimit, at, at, reason);

        eventRepository.append(java.util.List.of(event));
        // The id is scoped to tenant+customer: "ent-" + featureKey collided in the JDBC store
        // (whose primary key is entitlement_id) when two customers held the same feature.
        String entitlementId = "ent-" + tenantId.value() + "-" + customerId.value() + "-" + featureKey;
        com.saas.pricing.core.model.PlanCode plan = com.saas.pricing.core.model.PlanCode.of("");
        CustomerEntitlement record = switch (featureType) {
            case BOOLEAN -> CustomerEntitlement.booleanEntitlement(
                entitlementId, tenantId, customerId, plan, featureKey, true, at);
            case METERED_RECURRING, METERED_STATIC -> new CustomerEntitlement(
                entitlementId, tenantId, customerId, plan, featureKey, featureType, true,
                quotaLimit, java.math.BigDecimal.ZERO, true, at, java.util.Optional.empty());
        };
        entitlementRepository.saveEntitlement(record);
        outboxRepository.enqueue(DomainEventFactory.entitlementGranted(
            tenantId.value(), customerId.value(), featureKey, reason, at));
        return event;
    }

    /** Revokes an entitlement, recording the event and announcing it. */
    @Transactional
    public EntitlementEvent revoke(String eventId, TenantId tenantId, CustomerId customerId,
                                   String featureKey, String reason) {
        Instant at = clock.instant();
        CustomerEntitlement current = entitlementRepository
            .findEntitlement(tenantId, customerId, featureKey, at)
            .orElseThrow(() -> new IllegalArgumentException(
                "Cannot revoke '" + featureKey + "': the customer does not hold it"));
        CustomerEntitlement revoked = current.recordRevocation();
        Optional<CustomerEntitlement> existing = Optional.of(revoked);

        // The event carries the state BEFORE the change, which is what lets a consumer recognise a
        // re-delivery as "no change" rather than re-revoking.
        var previousState = new EntitlementEvent.EntitlementState(
            current.booleanValue(), current.type(), current.quotaLimit(),
            Optional.of(current.currentUsage()), current.effectiveFrom());

        EntitlementEvent event = EntitlementEvent.revoke(eventId, tenantId, customerId, featureKey,
            at, at, reason, previousState);

        eventRepository.append(java.util.List.of(event));
        if (existing.isPresent()) {
            entitlementRepository.saveEntitlement(existing.get());
        }
        outboxRepository.enqueue(DomainEventFactory.entitlementRevoked(
            tenantId.value(), customerId.value(), featureKey, reason, at));
        return event;
    }
}