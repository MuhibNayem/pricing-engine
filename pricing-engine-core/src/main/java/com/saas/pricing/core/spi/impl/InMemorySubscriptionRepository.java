package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.subscription.Subscription;
import com.saas.pricing.core.spi.SubscriptionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory subscription store.
 *
 * <p>Mirrors the JDBC adapter, including refusing to "renew" a terminal subscription: a cancelled
 * one that renews itself back to life is the exact failure the absorbing terminal states exist to
 * prevent.
 */
public class InMemorySubscriptionRepository implements SubscriptionRepository {

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    private static String key(TenantId tenantId, String subscriptionId) {
        return tenantId.value().toUpperCase(java.util.Locale.ROOT) + "::" + subscriptionId;
    }

    @Override
    public void create(Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription cannot be null");
        if (subscriptions.putIfAbsent(key(subscription.tenantId(), subscription.subscriptionId()),
                subscription) != null) {
            throw new IllegalStateException(
                "Subscription " + subscription.subscriptionId() + " already exists");
        }
    }

    @Override
    public void update(Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription cannot be null");
        String k = key(subscription.tenantId(), subscription.subscriptionId());
        if (subscriptions.replace(k, subscription) == null) {
            throw new IllegalStateException(
                "Unknown subscription " + subscription.subscriptionId());
        }
    }

    @Override
    public Optional<Subscription> find(TenantId tenantId, String subscriptionId) {
        return Optional.ofNullable(subscriptions.get(key(tenantId, subscriptionId)));
    }

    @Override
    public List<Subscription> findByCustomer(TenantId tenantId, CustomerId customerId) {
        Objects.requireNonNull(customerId, "customerId cannot be null");
        List<Subscription> found = new ArrayList<>();
        subscriptions.forEach((k, v) -> {
            if (v.tenantId().equals(tenantId) && v.customerId().equals(customerId)) {
                found.add(v);
            }
        });
        found.sort(Comparator.comparing(Subscription::subscriptionId));
        return found;
    }

    @Override
    public List<Subscription> findDueForRenewal(TenantId tenantId, Instant at) {
        Objects.requireNonNull(at, "at cannot be null");
        List<Subscription> due = new ArrayList<>();
        subscriptions.forEach((k, v) -> {
            if (v.tenantId().equals(tenantId)
                    && !v.status().isTerminal()
                    && !v.currentPeriodEnd().isAfter(at)) {
                due.add(v);
            }
        });
        due.sort(Comparator.comparing(Subscription::currentPeriodEnd));
        return due;
    }

    public void clear() {
        subscriptions.clear();
    }
}