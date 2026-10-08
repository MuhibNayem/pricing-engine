package com.saas.pricing.core.spi;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * High-performance caching SPI for rate cards, FX exchange rates, and pricing metadata.
 */
public interface CacheProvider<K, V> {

    /**
     * Gets a value from cache if present.
     */
    Optional<V> get(K key);

    /**
     * Puts a value into cache.
     */
    void put(K key, V value);

    /**
     * Computes value if absent in cache.
     */
    default V computeIfAbsent(K key, Supplier<V> supplier) {
        Optional<V> existing = get(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        V computed = supplier.get();
        if (computed != null) {
            put(key, computed);
        }
        return computed;
    }

    /**
     * Invalidates a specific key.
     */
    void invalidate(K key);

    /**
     * Clears all entries in the cache.
     */
    void clear();
}
