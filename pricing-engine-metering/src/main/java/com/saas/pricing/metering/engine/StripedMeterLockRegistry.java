package com.saas.pricing.metering.engine;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.MeterLockRegistry;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Striped, in-JVM lock registry providing mutual exclusion per {@code (tenant, meter)} pair.
 *
 * <p>The aggregation cache is cache-aside: {@link DefaultUsageMeteringEngine#aggregate} reads the cache,
 * recomputes on a miss and writes the result back, while ingestion concurrently saves an event and
 * invalidates the affected windows. Without mutual exclusion an ingest that lands between the event
 * scan and the cache write is masked by a stale re-save, and the window is permanently under-billed.
 * Both paths therefore take the same stripe lock, which makes read-modify-aggregate-write atomic
 * against ingestion.</p>
 *
 * <h2>Why stripes rather than one lock per key</h2>
 * <p>A {@code ConcurrentHashMap}-backed lock-per-key registry gives perfect isolation but grows without
 * bound: a lock object (plus its ReentrantLock state and CHM node, ~100 bytes) is retained for every
 * distinct {@code (tenant, meter, window)} ever aggregated, and entries would have to be reference-counted
 * and evicted to avoid a leak. Windows are effectively unbounded in the long-lived billing windows a
 * metering engine serves, so that leak is structural rather than an edge case.</p>
 * <p>A fixed stripe array is bounded by construction (1024 locks, allocated once, ~50 KB) and needs no
 * eviction. The cost is that two unrelated windows of the same tenant+meter may occasionally share a
 * stripe and serialise. This is deliberate: striping is keyed on {@code (tenant, meter)} — not on
 * {@code (tenant, meter, window)} — because ingestion cannot know every window an arbitrary event
 * timestamp belongs to, so both sides must agree on a key that is computable from the event alone.</p>
 *
 * <p>This registry is JVM-local: it does not coordinate two application instances. Use an
 * advisory-lock implementation (see the persistence module) when the stores are shared.</p>
 */
public class StripedMeterLockRegistry implements MeterLockRegistry {

    /** Fixed, power-of-two stripe count. Bounded memory, no eviction bookkeeping. */
    public static final int DEFAULT_STRIPE_COUNT = 1024;

    private final ReentrantLock[] stripes;

    public StripedMeterLockRegistry() {
        this(DEFAULT_STRIPE_COUNT);
    }

    public StripedMeterLockRegistry(int stripeCount) {
        if (stripeCount <= 0 || Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException("stripeCount must be a positive power of two");
        }
        this.stripes = new ReentrantLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * Returns the lock guarding the given tenant+meter. Callers must not rely on the stripe being
     * exclusive to this exact key — two keys may legitimately share one.
     */
    @Override
    public ReentrantLock lockFor(TenantId tenantId, String meterCode) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        // Locale.ROOT: bare toUpperCase() is locale-dependent (Turkish 'i'), so the lock key and a
        // case-insensitive event filter could disagree on some JVMs and lock different stripes.
        String key = tenantId.value() + "::" + meterCode.toUpperCase(Locale.ROOT);
        int hash = key.hashCode();
        int index = (hash ^ (hash >>> 16)) & (stripes.length - 1);
        return stripes[index];
    }

    public int stripeCount() {
        return stripes.length;
    }
}
