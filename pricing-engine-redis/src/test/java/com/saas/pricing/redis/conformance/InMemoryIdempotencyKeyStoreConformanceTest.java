package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.spi.IdempotencyKeyStore;
import com.saas.pricing.core.spi.impl.InMemoryIdempotencyKeyStore;

import org.junit.jupiter.api.DisplayName;

/**
 * Runs the shared contract against the in-memory reference implementation.
 *
 * <p>This is the older of the two and predates the conformance suite. Running it here is what makes
 * the suite meaningful: if only Redis passed, the suite would just be a description of the adapter
 * rather than a contract both must meet.</p>
 */
@DisplayName("IdempotencyKeyStore conformance — in-memory")
class InMemoryIdempotencyKeyStoreConformanceTest extends IdempotencyKeyStoreConformance {

    @Override
    protected IdempotencyKeyStore store() {
        return new InMemoryIdempotencyKeyStore();
    }
}