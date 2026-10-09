package com.saas.pricing.core.spi;

/**
 * Marker interface for beans whose internal state can be wiped during test fixture resets.
 *
 * <p>Production code must never invoke {@link #resetForTesting()}. The method exists solely
 * for {@code FixtureResetTestExecutionListener} and similar test infrastructure that needs
 * to guarantee isolation across tests sharing a cached Spring ApplicationContext.
 *
 * <p>Implementors should clear <strong>all</strong> accumulated state (maps, counters, queues)
 * so the bean behaves as if freshly constructed.
 */
public interface ResettableForTesting {

    /**
     * Resets all mutable state to its initial (empty) condition.
     * This method is <strong>test-only</strong>; calling it in production destroys live data.
     */
    void resetForTesting();
}
