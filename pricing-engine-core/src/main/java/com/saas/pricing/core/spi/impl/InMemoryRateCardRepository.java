package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Enterprise thread-safe in-memory rate card repository.
 *
 * <p>Extends {@link BasePersistentRateCardRepository} so the in-memory store enforces the same
 * versioning contract and deterministic version ordering as the JDBC store. Implementing the
 * interface directly - as an earlier version did - silently skipped both.
 */
public class InMemoryRateCardRepository extends BasePersistentRateCardRepository {

    private final Map<String, List<RateCard>> store = new ConcurrentHashMap<>();

    private String key(TenantId tenantId, PlanCode planCode) {
        return tenantId.value() + "::" + planCode.value();
    }

    @Override
    protected List<RateCard> loadRawCards(TenantId tenantId, PlanCode planCode) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        List<RateCard> cards = store.get(key(tenantId, planCode));
        return cards == null ? List.of() : List.copyOf(cards);
    }

    @Override
    protected void persistCard(RateCard rateCard) {
        store.computeIfAbsent(key(rateCard.tenantId(), rateCard.planCode()), k -> new CopyOnWriteArrayList<>())
            .add(rateCard);
    }

    @Override
    public Optional<RateCard> findGlobalRateCard(PlanCode planCode, Instant effectiveTime) {
        return findEffectiveRateCard(TenantId.of("GLOBAL"), planCode, effectiveTime);
    }

    @Override
    public Optional<RateCard> findBiTemporalGlobalRateCard(PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        return findBiTemporalRateCard(TenantId.of("GLOBAL"), planCode, effectiveTime, systemTime);
    }

    public List<RateCard> findAllVersions(TenantId tenantId, PlanCode planCode) {
        return new ArrayList<>(loadRawCards(tenantId, planCode));
    }

    public void clear() {
        store.clear();
    }
}
