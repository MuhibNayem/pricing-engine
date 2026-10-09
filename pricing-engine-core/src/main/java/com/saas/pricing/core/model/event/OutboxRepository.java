package com.saas.pricing.core.model.event;

import com.saas.pricing.core.model.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Persistence for the transactional outbox.
 *
 * <p>Writes happen <em>in the same transaction</em> as the state change they describe, which is the
 * whole point: it makes "state changed but event lost" impossible.
 *
 * <p>Reads are delivery-oriented. {@link #findDue(Instant)} is what a dispatcher polls, and
 * {@link #recordDelivery(OutboxEvent)} writes the outcome back. Attempts are never deleted, because
 * an event that was never delivered is exactly the thing an operator needs to find afterwards.
 */
public interface OutboxRepository {

    /**
     * Queues an event.
     *
     * @throws IllegalArgumentException if the event id is already queued with different content
     */
    void enqueue(OutboxEvent event);

    /** Writes the outcome of a delivery attempt: delivered, failed, or still pending. */
    void recordDelivery(OutboxEvent event);

    /** Events eligible for a delivery attempt at {@code at}, oldest first. */
    List<OutboxEvent> findDue(Instant at, int limit);

    /** Every event for one tenant, for an operator replaying or auditing history. */
    List<OutboxEvent> findByTenant(TenantId tenantId, int limit);

    /**
     * Events that exhausted their delivery budget without succeeding.
     *
     * <p>These are the ones that need a human: the state change committed, but the subscriber was
     * never told.
     */
    List<OutboxEvent> findUndelivered(String topic, int limit);
}