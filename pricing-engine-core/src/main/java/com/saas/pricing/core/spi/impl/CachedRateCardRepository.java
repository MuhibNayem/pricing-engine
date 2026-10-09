package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.CacheProvider;
import com.saas.pricing.core.spi.RateCardRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * High-performance caching decorator for RateCardRepository.
 */
public class CachedRateCardRepository implements RateCardRepository {

    private final RateCardRepository delegate;
    private final CacheProvider<String, Optional<RateCard>> cache;

    public CachedRateCardRepository(RateCardRepository delegate, CacheProvider<String, Optional<RateCard>> cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate RateCardRepository cannot be null");
        this.cache = Objects.requireNonNull(cache, "CacheProvider cannot be null");
    }

    private String cacheKey(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
        return tenantId.value() + ":" + planCode.value() + ":" + effectiveTime;
    }

    @Override
    public Optional<RateCard> findEffectiveRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
        String key = cacheKey(tenantId, planCode, effectiveTime);
        return cache.computeIfAbsent(key, () -> delegate.findEffectiveRateCard(tenantId, planCode, effectiveTime));
    }

    @Override
    public Optional<RateCard> findBiTemporalRateCard(TenantId tenantId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        String key = cacheKey(tenantId, planCode, effectiveTime) + ":" + systemTime.toEpochMilli();
        return cache.computeIfAbsent(key, () -> delegate.findBiTemporalRateCard(tenantId, planCode, effectiveTime, systemTime));
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
        delegate.save(rateCard);
        cache.clear(); // invalidate cache on mutation
    }
}
