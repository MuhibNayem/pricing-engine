package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.spi.FxRateCache;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory implementation of {@link FxRateCache}.
 */
public class ConcurrentMapFxRateCache implements FxRateCache {

    private final Map<String, BigDecimal> cache = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    @Override
    public Optional<BigDecimal> get(String pair) {
        Objects.requireNonNull(pair, "pair cannot be null");
        BigDecimal val = cache.get(pair);
        if (val != null) {
            hitCount.incrementAndGet();
            return Optional.of(val);
        }
        missCount.incrementAndGet();
        return Optional.empty();
    }

    @Override
    public void put(String pair, BigDecimal rate) {
        Objects.requireNonNull(pair, "pair cannot be null");
        Objects.requireNonNull(rate, "rate cannot be null");
        cache.put(pair, rate);
    }

    @Override
    public void clear() {
        cache.clear();
    }

    public long getHitCount() {
        return hitCount.get();
    }

    public long getMissCount() {
        return missCount.get();
    }

    public int size() {
        return cache.size();
    }
}
