package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.subscription.Subscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for subscriptions.
 *
 * <p>Unlike the ledgers, a subscription row <em>is</em> updated in place: it holds current state,
 * not history. The history lives in the outbox, which announces every transition.
 *
 * <p>This is not optional infrastructure. A cancelled subscription that is not persisted returns
 * to life when the process restarts, so the customer keeps access they stopped paying for and the
 * credit note already issued can never be reconciled.
 */
public interface SubscriptionRepository {

    /**
     * Inserts a new subscription.
     *
     * @throws IllegalStateException if the subscription id already exists
     */
    void create(Subscription subscription);

    /**
     * Replaces a subscription's current state.
     *
     * @throws IllegalStateException if the subscription does not exist
     */
    void update(Subscription subscription);

    Optional<Subscription> find(TenantId tenantId, String subscriptionId);

    /** Every subscription a customer holds, live or terminal. */
    List<Subscription> findByCustomer(TenantId tenantId, CustomerId customerId);

    /**
     * Subscriptions whose billing period ends at or before {@code at}.
     *
     * <p>Drives renewal. Terminal subscriptions are excluded because a cancelled one must not
     * renew itself back to life.
     */
    List<Subscription> findDueForRenewal(TenantId tenantId, Instant at);
}