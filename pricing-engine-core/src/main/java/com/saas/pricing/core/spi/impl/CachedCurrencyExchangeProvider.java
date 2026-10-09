package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;
import com.saas.pricing.core.spi.FxRateCache;
import com.saas.pricing.core.spi.ResettableForTesting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Caching decorator for {@link CurrencyExchangeProvider}.
 *
 * <p>Uses a purpose-typed {@link FxRateCache} — not the general-purpose {@code CacheProvider} —
 * so there is no generic-erasure ambiguity when a host registers both an FX cache and a
 * differently-keyed cache for rate cards.
 */
public class CachedCurrencyExchangeProvider implements CurrencyExchangeProvider, ResettableForTesting {

    private final CurrencyExchangeProvider delegate;
    private final FxRateCache cache;

    public CachedCurrencyExchangeProvider(CurrencyExchangeProvider delegate, FxRateCache cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate CurrencyExchangeProvider cannot be null");
        this.cache = Objects.requireNonNull(cache, "FxRateCache cannot be null");
    }

    private String key(CurrencyUnit from, CurrencyUnit to, Instant timestamp) {
        // Quantize timestamp to 10-minute window for FX caching
        long window = timestamp.getEpochSecond() / 600;
        return from.code() + ":" + to.code() + ":" + window;
    }

    @Override
    public BigDecimal getExchangeRate(CurrencyUnit from, CurrencyUnit to, Instant timestamp) {
        if (from.equals(to)) return BigDecimal.ONE;
        String k = key(from, to, timestamp);
        return cache.computeIfAbsent(k, () -> delegate.getExchangeRate(from, to, timestamp));
    }

    /**
     * Invalidates all cached exchange rates.
     *
     * <p>This does <strong>not</strong> touch the delegate's authoritative rate table;
     * subsequent lookups simply re-populate the cache from the delegate.
     */
    public void clearCache() {
        cache.clear();
    }

    @Override
    public void resetForTesting() {
        cache.clear();
        if (delegate instanceof ResettableForTesting resettable) {
            resettable.resetForTesting();
        }
    }
}
