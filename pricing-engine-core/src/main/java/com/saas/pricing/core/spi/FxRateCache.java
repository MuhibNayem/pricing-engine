package com.saas.pricing.core.spi;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Purpose-typed cache for foreign-exchange rates.
 *
 * <p>Separating FX caching from the general-purpose {@link CacheProvider} eliminates the
 * generic-erasure ambiguity that required runtime type introspection to distinguish a
 * {@code CacheProvider<String, BigDecimal>} (suitable for FX) from a differently-keyed
 * provider registered by the host for rate-card caching.
 *
 * <p>A host that needs custom FX caching (e.g. Redis-backed, TTL-aware) registers a bean
 * of this type; the auto-configuration picks it up via {@code @ConditionalOnMissingBean}.
 */
public interface FxRateCache {

    /**
     * Returns the cached exchange rate for the given composite key, if present.
     *
     * @param pair composite key (e.g. {@code "USD:EUR:174823"}) encoding the currency pair
     *             and the time-window bucket
     * @return the cached rate, or empty if no entry exists
     */
    Optional<BigDecimal> get(String pair);

    /**
     * Stores an exchange rate in the cache.
     *
     * @param pair composite key
     * @param rate the exchange rate to cache
     */
    void put(String pair, BigDecimal rate);

    /**
     * Computes the rate if absent in cache, storing and returning the result.
     *
     * @param pair     composite key
     * @param supplier supplies the rate on cache miss
     * @return the cached or freshly-computed rate
     */
    default BigDecimal computeIfAbsent(String pair, java.util.function.Supplier<BigDecimal> supplier) {
        Optional<BigDecimal> existing = get(pair);
        if (existing.isPresent()) {
            return existing.get();
        }
        BigDecimal computed = supplier.get();
        if (computed != null) {
            put(pair, computed);
        }
        return computed;
    }

    /**
     * Removes all cached exchange rates.
     */
    void clear();
}
