package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.spi.CacheProvider;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Caching decorator for CurrencyExchangeProvider.
 */
public class CachedCurrencyExchangeProvider implements CurrencyExchangeProvider {

    private final CurrencyExchangeProvider delegate;
    private final CacheProvider<String, BigDecimal> cache;

    public CachedCurrencyExchangeProvider(CurrencyExchangeProvider delegate, CacheProvider<String, BigDecimal> cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate CurrencyExchangeProvider cannot be null");
        this.cache = Objects.requireNonNull(cache, "CacheProvider cannot be null");
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
}
