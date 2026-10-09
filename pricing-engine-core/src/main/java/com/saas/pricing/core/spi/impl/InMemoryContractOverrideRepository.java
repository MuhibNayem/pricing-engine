package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.hierarchy.ContractOverride;
import com.saas.pricing.core.spi.ContractOverrideRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory repository for negotiated contract overrides.
 */
public class InMemoryContractOverrideRepository implements ContractOverrideRepository {

    private final Map<String, List<ContractOverride>> store = new ConcurrentHashMap<>();

    private String key(TenantId tenantId, CustomerId customerId, PlanCode planCode) {
        return tenantId.value() + "::" + customerId.value() + "::" + planCode.value();
    }

    @Override
    public Optional<ContractOverride> findEffectiveOverride(TenantId tenantId, CustomerId customerId, PlanCode planCode, Instant effectiveTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");

        List<ContractOverride> list = store.get(key(tenantId, customerId, planCode));
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }

        return list.stream()
            .filter(o -> o.isEffectiveAt(effectiveTime))
            .max(Comparator.comparingInt(ContractOverride::version));
    }

    @Override
    public Optional<ContractOverride> findBiTemporalOverride(TenantId tenantId, CustomerId customerId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");
        Objects.requireNonNull(systemTime, "systemTime cannot be null");

        List<ContractOverride> list = store.get(key(tenantId, customerId, planCode));
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }

        return list.stream()
            .filter(o -> o.isEffectiveAt(effectiveTime) && o.isRecordValidAt(systemTime))
            .max(Comparator.comparingInt(ContractOverride::version));
    }

    @Override
    public void save(ContractOverride override) {
        Objects.requireNonNull(override, "override cannot be null");
        String k = key(override.tenantId(), override.customerId(), override.planCode());
        store.computeIfAbsent(k, key -> new CopyOnWriteArrayList<>()).add(override);
    }

    public void clear() {
        store.clear();
    }
}
