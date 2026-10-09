package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.EntitlementEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only storage for entitlement lifecycle events.
 *
 * <p>Entitlement state is derived by replaying these events (see
 * {@code EntitlementStateProjection}), so this repository is the durable source of that derivation.
 * It therefore has the same obligations as the wallet ledger:
 *
 * <ul>
 *   <li><strong>Append-only.</strong> There is no update or delete. A correction is a new event.</li>
 *   <li><strong>Idempotent append.</strong> Re-appending an identical event (a redelivery) is a
 *       no-op; re-using an id for <em>different</em> content is refused. That is what lets an
 *       at-least-once transport feed it safely.</li>
 *   <li><strong>Ordered reads.</strong> Events come back in {@code (effectiveAt, recordedAt,
 *       eventId)} order, because that is the order the projection replays them in.</li>
 * </ul>
 */
public interface EntitlementEventRepository {

    /**
     * Appends events.
     *
     * @throws IllegalArgumentException if an event id already exists with different content
     */
    void append(List<EntitlementEvent> events);

    /**
     * Every event for one customer and feature, in replay order.
     *
     * @param effectiveBefore optionally restrict to events effective at or before this instant
     */
    List<EntitlementEvent> findEvents(TenantId tenantId, CustomerId customerId, String featureKey,
                                      Optional<Instant> effectiveBefore);

    /** Every event for one customer, in replay order. */
    List<EntitlementEvent> findAllEvents(TenantId tenantId, CustomerId customerId,
                                         Optional<Instant> effectiveBefore);

    /** The latest event for a feature, or empty if the customer has never held it. */
    Optional<EntitlementEvent> findLatest(TenantId tenantId, CustomerId customerId, String featureKey);
}