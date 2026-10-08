package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.spi.CacheProvider;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe concurrent map implementation of CacheProvider with hit/miss counters.
 */
public class ConcurrentMapCacheProvider<K, V> implements CacheProvider<K, V> {

    private final Map<K, V> cache = new ConcurrentHashMap<>();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key cannot be null");
        V val = cache.get(key);
        if (val != null) {
            hitCount.incrementAndGet();
            return Optional.of(val);
        } else {
            missCount.incrementAndGet();
            return Optional.empty();
        }
    }

    @Override
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        cache.put(key, value);
    }

    @Override
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key cannot be null");
        cache.remove(key);
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
