package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.RatingClaimStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
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
 *
 * <h2>Expiry is evaluated at read time, not by a sweep</h2>
 *
 * <p>Every method checks {@link ClaimEntry#isExpired} itself. Correctness therefore does not depend
 * on how often entries are reclaimed, which is what makes the bounded sweep below safe: an entry a
 * sweep has not reached yet is still correctly reported as absent.</p>
 *
 * <p>The previous implementation visited <em>every</em> entry on <em>every</em> call through
 * {@code purgeExpired()}, making the exact, complete pass load-bearing for correctness as well as
 * memory — an O(n) cost under a single monitor, paid four times over because every method on the
 * interface swept.</p>
 *
 * <h2>Reclamation is bounded work on a write-ordered queue</h2>
 *
 * <p>Expired claims are reclaimed from a write-order queue, examining at most
 * {@link #SWEEP_BUDGET} nodes per sweep and sweeping at most once every
 * {@link #SWEEP_EVERY_OPERATIONS} operations. This is the fixed-expiration structure Caffeine uses
 * for {@code expireAfterWrite}, and Redis's shape: bounded work per cycle instead of a full pass.</p>
 *
 * <p>Near the entry cap the sweep is forced and widened. No scheduler runs; a host that wants to
 * drive reclamation on a timer can call {@link #purgeExpired()}.</p>
 */
public class InMemoryRatingClaimStore implements RatingClaimStore, ResettableForTesting {

    /** Retained long enough to cover any plausible correction window. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(90);

    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    /** Operations between sweeps while the map sits comfortably below its cap. */
    static final int SWEEP_EVERY_OPERATIONS = 128;

    /** Nodes examined per sweep under normal load. */
    static final int SWEEP_BUDGET = 256;

    /** Nodes examined per sweep once the map is within 20% of its cap. */
    static final int SWEEP_BUDGET_UNDER_PRESSURE = 4096;

    private static final int QUEUE_SLACK = 64;

    private final Map<String, ClaimEntry> claims;
    private final Map<String, ExpiryNode> expiryIndex;
    private final ArrayDeque<ExpiryNode> expiryQueue;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;

    private int operationsSinceSweep;

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
        this.expiryIndex = new HashMap<>(1024, 0.75f);
        this.expiryQueue = new ArrayDeque<>(1024);
    }

    private static String key(TenantId tenantId, String claimKey) {
        return tenantId.value() + "::" + claimKey;
    }

    @Override
    public synchronized Optional<Charged> find(TenantId tenantId, String claimKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Instant now = clock.instant();
        sweepIfDue(now);
        ClaimEntry entry = claims.get(key(tenantId, claimKey));
        if (entry == null || entry.isExpired(now, ttl)) {
            return Optional.empty();
        }
        return Optional.of(entry.charged());
    }

    @Override
    public synchronized void record(TenantId tenantId, String claimKey, Charged charged) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(charged, "charged cannot be null");
        Instant now = clock.instant();
        sweepIfDue(now);
        store(key(tenantId, claimKey), new ClaimEntry(charged, now));
    }

    @Override
    public synchronized boolean compareAndSet(TenantId tenantId, String claimKey,
                                              Optional<Charged> expected, Charged updated) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");
        Objects.requireNonNull(updated, "updated cannot be null");
        Instant now = clock.instant();
        sweepIfDue(now);

        String composite = key(tenantId, claimKey);
        ClaimEntry current = liveEntry(composite, now);
        boolean matches = expected
            .map(wanted -> current != null && current.charged().equals(wanted))
            .orElse(current == null);
        if (!matches) {
            return false;
        }
        store(composite, new ClaimEntry(updated, now));
        return true;
    }

    @Override
    public synchronized boolean remove(TenantId tenantId, String claimKey, Charged expected) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(claimKey, "claimKey cannot be null");
        Objects.requireNonNull(expected, "expected cannot be null");
        Instant now = clock.instant();
        sweepIfDue(now);

        String composite = key(tenantId, claimKey);
        ClaimEntry current = liveEntry(composite, now);
        if (current == null || !current.charged().equals(expected)) {
            return false;
        }
        detach(composite);
        return true;
    }

    /** Writes an entry and queues it for reclamation. Any previous node for the key becomes a tombstone. */
    private void store(String composite, ClaimEntry entry) {
        ExpiryNode node = new ExpiryNode(composite, entry);
        claims.put(composite, entry);
        expiryIndex.put(composite, node);
        expiryQueue.addLast(node);
    }

    /** The stored entry for {@code composite}, or null if absent or expired. Expired entries are dropped. */
    private ClaimEntry liveEntry(String composite, Instant now) {
        ClaimEntry entry = claims.get(composite);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(now, ttl)) {
            detach(composite);
            return null;
        }
        return entry;
    }

    private void detach(String composite) {
        claims.remove(composite);
        expiryIndex.remove(composite);
    }

    private void sweepIfDue(Instant now) {
        boolean underPressure = claims.size() >= maxEntries - (maxEntries / 5);
        if (!underPressure && ++operationsSinceSweep < SWEEP_EVERY_OPERATIONS) {
            return;
        }
        operationsSinceSweep = 0;
        sweepExpired(now, underPressure ? SWEEP_BUDGET_UNDER_PRESSURE : SWEEP_BUDGET);
        compactQueueIfNeeded();
    }

    private void sweepExpired(Instant now, int budget) {
        Instant cutoff = now.minus(ttl);
        Iterator<ExpiryNode> iterator = expiryQueue.iterator();
        int examined = 0;
        while (examined < budget && iterator.hasNext()) {
            ExpiryNode node = iterator.next();
            examined++;
            if (node.writtenAt().isAfter(cutoff)) {
                continue;
            }
            iterator.remove();
            if (expiryIndex.get(node.composite()) == node) {
                expiryIndex.remove(node.composite());
                claims.remove(node.composite());
            }
        }
    }

    private void compactQueueIfNeeded() {
        if (expiryQueue.size() <= 2 * expiryIndex.size() + QUEUE_SLACK) {
            return;
        }
        expiryQueue.clear();
        expiryQueue.addAll(expiryIndex.values());
    }

    /**
     * Reclaims every currently-expired claim, ignoring the operation interval and budget.
     *
     * <p>Exposed so a host with its own scheduler can drive reclamation on a timer. This store
     * starts no threads; correctness never depends on this being called.</p>
     *
     * @return the number of claims reclaimed
     */
    public synchronized int purgeExpired() {
        Instant now = clock.instant();
        int before = claims.size();
        sweepExpired(now, Integer.MAX_VALUE);
        compactQueueIfNeeded();
        return before - claims.size();
    }

    @Override
    public synchronized void resetForTesting() {
        claims.clear();
        expiryIndex.clear();
        expiryQueue.clear();
        operationsSinceSweep = 0;
    }

    private record ExpiryNode(String composite, ClaimEntry entry) {
        Instant writtenAt() {
            return entry.writtenAt();
        }
    }

    private record ClaimEntry(Charged charged, Instant writtenAt) {

        /** Same comparison the old sweep used, so a claim expires at exactly the instant it did before. */
        boolean isExpired(Instant now, Duration ttl) {
            return writtenAt.plus(ttl).isBefore(now);
        }
    }
}