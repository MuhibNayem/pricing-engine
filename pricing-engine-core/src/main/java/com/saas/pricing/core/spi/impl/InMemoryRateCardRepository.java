package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.RateCardRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Enterprise thread-safe in-memory rate card repository.
 * Fully supports bi-temporal queries, version history, and global catalog fallbacks.
 */
public class InMemoryRateCardRepository implements RateCardRepository {

    private final Map<String, List<RateCard>> store = new ConcurrentHashMap<>();

    private String key(TenantId tenantId, PlanCode planCode) {
        return tenantId.value().toUpperCase() + "::" + planCode.value().toUpperCase();
    }

    @Override
    public Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");

        List<RateCard> cards = store.get(key(tenantId, planCode));
        if (cards == null || cards.isEmpty()) {
            return Optional.empty();
        }

        return cards.stream()
            .filter(rc -> rc.isEffectiveAt(effectiveTime))
            .max(Comparator.comparingInt(RateCard::version));
    }

    @Override
    public Optional<RateCard> findBiTemporalRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(effectiveTime, "effectiveTime cannot be null");
        Objects.requireNonNull(systemTime, "systemTime cannot be null");

        List<RateCard> cards = store.get(key(tenantId, planCode));
        if (cards == null || cards.isEmpty()) {
            return Optional.empty();
        }

        return cards.stream()
            .filter(rc -> rc.isBiTemporallyValidAt(effectiveTime, systemTime))
            .max(Comparator.comparingInt(RateCard::version));
    }

    @Override
    public Optional<RateCard> findGlobalRateCard(PlanCode planCode, Instant effectiveTime) {
        return findEffectiveRateCard(TenantId.of("GLOBAL"), planCode, effectiveTime);
    }

    @Override
    public Optional<RateCard> findBiTemporalGlobalRateCard(PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        return findBiTemporalRateCard(TenantId.of("GLOBAL"), planCode, effectiveTime, systemTime);
    }

    @Override
    public void save(RateCard rateCard) {
        Objects.requireNonNull(rateCard, "rateCard cannot be null");
        String k = key(rateCard.tenantId(), rateCard.planCode());
        store.computeIfAbsent(k, key -> new CopyOnWriteArrayList<>()).add(rateCard);
    }

    public List<RateCard> findAllVersions(TenantId tenantId, PlanCode planCode) {
        List<RateCard> cards = store.get(key(tenantId, planCode));
        return cards != null ? new ArrayList<>(cards) : List.of();
    }

    public void clear() {
        store.clear();
    }
}
