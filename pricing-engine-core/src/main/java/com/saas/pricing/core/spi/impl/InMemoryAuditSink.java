package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AuditSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe queryable in-memory audit sink for testing, auditing, and ledger verification.
 */
public class InMemoryAuditSink implements AuditSink, ResettableForTesting {

    private final Map<String, PricingResult> resultsById = new ConcurrentHashMap<>();
    private final Map<String, List<PricingResult>> resultsByTenant = new ConcurrentHashMap<>();

    @Override
    public void record(PricingResult result) {
        Objects.requireNonNull(result, "PricingResult cannot be null");
        resultsById.put(result.calculationId(), result);
        resultsByTenant.computeIfAbsent(result.tenantId().value(), k -> new CopyOnWriteArrayList<>()).add(result);
    }

    public Optional<PricingResult> findById(String calculationId) {
        return Optional.ofNullable(resultsById.get(calculationId));
    }

    public List<PricingResult> findByTenant(TenantId tenantId) {
        List<PricingResult> list = resultsByTenant.get(tenantId.value());
        return list != null ? new ArrayList<>(list) : List.of();
    }

    public List<PricingResult> allResults() {
        return new ArrayList<>(resultsById.values());
    }

    public int count() {
        return resultsById.size();
    }

    @Override
    public void resetForTesting() {
        resultsById.clear();
        resultsByTenant.clear();
    }
}
