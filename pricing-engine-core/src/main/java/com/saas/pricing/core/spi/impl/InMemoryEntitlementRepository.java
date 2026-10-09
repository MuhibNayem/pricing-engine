package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.spi.EntitlementRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory customer entitlement repository.
 */
public class InMemoryEntitlementRepository implements EntitlementRepository, ResettableForTesting {

    private final Map<String, CustomerEntitlement> store = new ConcurrentHashMap<>();

    private String key(TenantId tenantId, CustomerId customerId, String featureKey) {
        return tenantId.value() + "::" + customerId.value() + "::" + featureKey;
    }

    private String prefix(TenantId tenantId, CustomerId customerId) {
        return tenantId.value() + "::" + customerId.value() + "::";
    }

    @Override
    public Optional<CustomerEntitlement> findEntitlement(TenantId tenantId, CustomerId customerId, String featureKey, Instant timestamp) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        CustomerEntitlement entitlement = store.get(key(tenantId, customerId, featureKey));
        if (entitlement != null && entitlement.isEffectiveAt(timestamp)) {
            return Optional.of(entitlement);
        }
        return Optional.empty();
    }

    @Override
    public List<CustomerEntitlement> findAllEntitlements(TenantId tenantId, CustomerId customerId, Instant timestamp) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        String p = prefix(tenantId, customerId);
        return store.entrySet().stream()
            .filter(e -> e.getKey().startsWith(p))
            .map(Map.Entry::getValue)
            .filter(ent -> ent.isEffectiveAt(timestamp))
            .toList();
    }

    @Override
    public void saveEntitlement(CustomerEntitlement entitlement) {
        Objects.requireNonNull(entitlement, "entitlement cannot be null");
        store.put(key(entitlement.tenantId(), entitlement.customerId(), entitlement.featureKey()), entitlement);
    }

    @Override
    public void recordUsage(TenantId tenantId, CustomerId customerId, String featureKey, BigDecimal usageDelta) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(usageDelta, "usageDelta cannot be null");

        String k = key(tenantId, customerId, featureKey);
        store.computeIfPresent(k, (key, existing) -> existing.recordUsage(usageDelta));
    }

    @Override
    public void resetForTesting() {
        store.clear();
    }
}
