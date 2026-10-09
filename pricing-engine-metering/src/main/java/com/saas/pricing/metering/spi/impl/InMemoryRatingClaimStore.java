package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.RatingClaimStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded, expiring in-memory rating-claim store.
 *
 * <p>Suitable for a single-node deployment. The map is capped and expired claims are purged on
 * access; the previous completion cache never forgot a window and grew for the process lifetime.
 * A clustered deployment uses the JDBC store, which also provides the atomic compare-and-set the
 * cluster needs.</p>
 */
public class InMemoryRatingClaimStore implements RatingClaimStore {

    /** Retained long enough to cover any plausible correction window. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(90);

    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final Map<String, ClaimEntry> claims;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;

    public InMemoryRatingClaimStore() {
        this(Clock.systemUTC(), DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
    }

    public InMemoryRatingClaimStore(Clock clock, Duration ttl, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl cannot be null");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.claims = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ClaimEntry> eldest) {
                return size() > InMemoryRatingClaimStore.this.maxEntries;
            }
        };
    }

    private static String key(TenantId tenantId, String claimKey) {
        return tenantId.value() + "::" + claimKey;
    }

    @Override
    public synchronized Optional<Charged> find(TenantId tenantId, String claimKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        purgeExpired();
        ClaimEntry entry = claims.get(key(tenantId, claimKey));
        return entry == null ? Optional.empty() : Optional.of(entry.charged());
    }

    @Override
    public synchronized void record(TenantId tenantId, String claimKey, Charged charged) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(charged, "charged cannot be null");
        purgeExpired();
        claims.put(key(tenantId, claimKey), new ClaimEntry(charged, clock.instant()));
    }

    @Override
    public synchronized boolean compareAndSet(TenantId tenantId, String claimKey,
                                              Optional<Charged> expected, Charged updated) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");
        Objects.requireNonNull(updated, "updated cannot be null");
        purgeExpired();

        String composite = key(tenantId, claimKey);
        ClaimEntry current = claims.get(composite);
        boolean matches = expected
            .map(wanted -> current != null && current.charged().equals(wanted))
            .orElse(current == null);
        if (!matches) {
            return false;
        }
        claims.put(composite, new ClaimEntry(updated, clock.instant()));
        return true;
    }

    @Override
    public synchronized boolean remove(TenantId tenantId, String claimKey, Charged expected) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");
        purgeExpired();

        String composite = key(tenantId, claimKey);
        ClaimEntry current = claims.get(composite);
        if (current == null || !current.charged().equals(expected)) {
            return false;
        }
        claims.remove(composite);
        return true;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, ClaimEntry>> iterator = claims.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().writtenAt().plus(ttl).isBefore(now)) {
                iterator.remove();
            }
        }
    }

    public synchronized void clear() {
        claims.clear();
    }

    private record ClaimEntry(Charged charged, Instant writtenAt) {
    }
}
