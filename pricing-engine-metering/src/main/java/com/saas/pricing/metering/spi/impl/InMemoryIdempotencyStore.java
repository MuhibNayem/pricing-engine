package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.IdempotencyStore;

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
import java.util.concurrent.CompletableFuture;

/**
 * Thread-safe in-memory implementation of IdempotencyStore.
 *
 * <p>Two independent maps are maintained:</p>
 * <ul>
 *   <li>{@code seenKeys} — per-event ingestion idempotency,
 *       {@code (tenant, idempotencyKey) -> (fingerprint, recordedInstant)}. Content-aware: the same
 *       key with different content is a {@link Claim#CONFLICT}, not a silent duplicate.</li>
 *   <li>{@code results} — per-charge in-flight completion handles used by window-level rating
 *       single-flight. The claim is an atomic {@code putIfAbsent}; the winning caller stores a
 *       completion handle that duplicate callers await.</li>
 * </ul>
 *
 * <p>Both maps are <strong>bounded</strong>: insertion-order eviction caps them, and seen keys also
 * expire after {@link #DEFAULT_TTL}. The previous unbounded maps grew for the lifetime of the
 * process on every event and every charge window.</p>
 *
 * <h2>Expiry is evaluated at read time, not by a sweep</h2>
 *
 * <p>Every read path checks {@link FingerprintEntry#isExpired} itself, the way Redis checks a
 * key's deadline on access and the way {@code InMemoryIdempotencyKeyStore} in core already does.
 * That makes correctness <em>independent</em> of how often entries are reclaimed: an entry that a
 * sweep has not reached yet is still correctly reported as absent.</p>
 *
 * <p>The previous implementation instead relied on {@code purgeExpired} visiting <em>every</em>
 * entry on <em>every</em> operation, which made the exact, complete sweep load-bearing for
 * correctness as well as for memory — an O(n) cost under a global monitor, measured at 434 us per
 * operation against a preloaded 100,000-entry store and degrading rather than plateauing.</p>
 *
 * <h2>Reclamation is bounded work on a write-ordered queue</h2>
 *
 * <p>Expired entries are reclaimed from a write-order queue, polling at most
 * {@link #SWEEP_BUDGET} nodes per sweep and running a sweep at most once every
 * {@link #SWEEP_EVERY_OPERATIONS} operations. This is the fixed-expiration structure Caffeine uses
 * for {@code expireAfterWrite} (a write-order deque; its timer wheel is reserved for the
 * <em>variable</em> {@code expireAfter(Expiry)} policy that does not apply to a fixed TTL), and it
 * is Redis's shape — bounded work per cycle rather than a full pass.</p>
 *
 * <p>Note that the queue is ordered by <em>record time</em>, which is the caller's
 * {@code eventTime} rather than insertion time. Metering ingests late and backdated events, so
 * record time is not monotonic with insertion and a sweep cannot stop at the first live node; it
 * examines its budget and takes whatever is due. That is sound precisely because read-time expiry
 * already guarantees correctness.</p>
 *
 * <p>Near the entry cap the sweep is forced and its budget widened, so the expensive pass is
 * reached only when memory actually demands it. No scheduler runs: this store starts no threads,
 * and a host that wants to drive reclamation on a timer can call {@link #purgeExpired()}.</p>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore, ResettableForTesting {

    /** Retention for an ingestion key. Longer than any plausible transport retry. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    /** Maximum retained ingestion keys and in-flight charge handles. */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    /**
     * Operations between sweeps while the map sits comfortably below its cap. At 128 operations
     * and a 256-node budget the amortised reclamation cost is about two node visits per operation.
     */
    static final int SWEEP_EVERY_OPERATIONS = 128;

    /** Nodes examined per sweep under normal load. */
    static final int SWEEP_BUDGET = 256;

    /** Nodes examined per sweep once the map is within 20% of its cap. */
    static final int SWEEP_BUDGET_UNDER_PRESSURE = 4096;

    /**
     * Rebuild the queue once tombstones outnumber live entries by this margin. Replacing a key
     * appends a node and leaves the previous one behind, so a write-heavy workload accumulates
     * them; the rebuild is O(n) but amortised, like a hash table resize.
     */
    private static final int QUEUE_SLACK = 64;

    private final Map<String, FingerprintEntry> seenKeys;
    private final Map<String, Object> results;

    /** Live {@code key -> node} index. Bounded by {@link #maxEntries}. */
    private final Map<String, ExpiryNode> expiryIndex;

    /** Write-order queue of expiry nodes. May contain tombstones; see {@link #QUEUE_SLACK}. */
    private final ArrayDeque<ExpiryNode> expiryQueue;

    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;

    private int operationsSinceSweep;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this(clock, DEFAULT_TTL, DEFAULT_MAX_ENTRIES);
    }

    public InMemoryIdempotencyStore(Clock clock, Duration ttl, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl cannot be null");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.seenKeys = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FingerprintEntry> eldest) {
                return size() > InMemoryIdempotencyStore.this.maxEntries;
            }
        };
        this.results = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > InMemoryIdempotencyStore.this.maxEntries;
            }
        };
        this.expiryIndex = new HashMap<>(1024, 0.75f);
        this.expiryQueue = new ArrayDeque<>(1024);
    }

    private String compositeKey(TenantId tenantId, String idempotencyKey) {
        return tenantId.value() + "::" + idempotencyKey;
    }

    @Override
    public Claim claim(TenantId tenantId, String idempotencyKey, String fingerprint, Instant eventTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        String content = fingerprint == null ? "" : fingerprint;
        Instant now = clock.instant();
        String key = compositeKey(tenantId, idempotencyKey);

        synchronized (seenKeys) {
            sweepIfDue(now);

            FingerprintEntry existing = seenKeys.get(key);
            if (existing != null && existing.isExpired(now, ttl)) {
                // The key is free again: an expired claim must not answer DUPLICATE.
                detach(key);
                existing = null;
            }
            if (existing == null) {
                FingerprintEntry entry = new FingerprintEntry(content, eventTime != null ? eventTime : now);
                ExpiryNode node = new ExpiryNode(key, entry);
                seenKeys.put(key, entry);
                expiryIndex.put(key, node);
                expiryQueue.addLast(node);
                return Claim.CLAIMED;
            }
            return existing.fingerprint().equals(content) ? Claim.DUPLICATE : Claim.CONFLICT;
        }
    }

    @Override
    public boolean isDuplicate(TenantId tenantId, String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Instant now = clock.instant();
        String key = compositeKey(tenantId, idempotencyKey);
        synchronized (seenKeys) {
            sweepIfDue(now);
            FingerprintEntry existing = seenKeys.get(key);
            return existing != null && !existing.isExpired(now, ttl);
        }
    }

    /**
     * Atomically releases a recorded key, but only if it still holds exactly the value written by the
     * matching claim. A concurrent re-record by another caller is never removed.
     */
    @Override
    public boolean remove(TenantId tenantId, String idempotencyKey, Instant recordedTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(recordedTime, "recordedTime cannot be null");
        String key = compositeKey(tenantId, idempotencyKey);
        synchronized (seenKeys) {
            FingerprintEntry existing = seenKeys.get(key);
            if (existing == null || !existing.recordedAt().equals(recordedTime)) {
                return false;
            }
            detach(key);
            return true;
        }
    }

    /** Removes the live entry for {@code key} and its queue index. Leaves any queued tombstone. */
    private void detach(String key) {
        seenKeys.remove(key);
        expiryIndex.remove(key);
    }

    /**
     * Reclaims expired keys, up to a bounded number of nodes, and does nothing unless enough
     * operations have accumulated or the map has reached 80% of its cap.
     */
    private void sweepIfDue(Instant now) {
        boolean underPressure = seenKeys.size() >= maxEntries - (maxEntries / 5);
        if (!underPressure && ++operationsSinceSweep < SWEEP_EVERY_OPERATIONS) {
            return;
        }
        operationsSinceSweep = 0;
        sweepExpired(now, underPressure ? SWEEP_BUDGET_UNDER_PRESSURE : SWEEP_BUDGET);
        compactQueueIfNeeded();
    }

    /**
     * Removes up to {@code budget} nodes whose recorded time is past the TTL.
     *
     * <p>The queue is in record order, but record time is the caller's event time and is therefore
     * not monotonic with insertion — metering ingests late events deliberately. So the scan does not
     * stop at the first live node; it examines its budget and takes whatever is due.</p>
     */
    private void sweepExpired(Instant now, int budget) {
        Instant cutoff = now.minus(ttl);
        Iterator<ExpiryNode> iterator = expiryQueue.iterator();
        int examined = 0;
        while (examined < budget && iterator.hasNext()) {
            ExpiryNode node = iterator.next();
            examined++;
            if (node.recordedAt().isAfter(cutoff)) {
                continue;
            }
            iterator.remove();
            if (expiryIndex.get(node.key()) == node) {
                expiryIndex.remove(node.key());
                seenKeys.remove(node.key());
            }
        }
    }

    /** Rebuilds the queue when tombstones outnumber live nodes by more than {@link #QUEUE_SLACK}. */
    private void compactQueueIfNeeded() {
        if (expiryQueue.size() <= 2 * expiryIndex.size() + QUEUE_SLACK) {
            return;
        }
        expiryQueue.clear();
        expiryQueue.addAll(expiryIndex.values());
    }

    /**
     * Reclaims every currently-expired key, ignoring the operation interval and budget.
     *
     * <p>Exposed so a host with its own scheduler can drive reclamation on a timer. This store
     * starts no threads; correctness never depends on this being called.</p>
     *
     * @return the number of keys reclaimed
     */
    public int purgeExpired() {
        Instant now = clock.instant();
        synchronized (seenKeys) {
            int before = seenKeys.size();
            sweepExpired(now, Integer.MAX_VALUE);
            compactQueueIfNeeded();
            return before - seenKeys.size();
        }
    }

    /**
     * Atomically claims a result-cache entry. Exactly one caller across any number of contending
     * threads receives {@code true} for a given composite key.
     *
     * @return true if the claim was won by this caller; false if another caller already claimed the key.
     */
    public boolean putResultIfAbsent(String compositeKey, Object result) {
        Objects.requireNonNull(compositeKey, "compositeKey cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
        synchronized (results) {
            if (results.containsKey(compositeKey)) {
                return false;
            }
            results.put(compositeKey, result);
            return true;
        }
    }

    /**
     * Returns the handle claimed for a composite key, if any.
     */
    public <T> Optional<T> findResult(String compositeKey, Class<T> type) {
        Objects.requireNonNull(compositeKey, "compositeKey cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        synchronized (results) {
            Object val = results.get(compositeKey);
            return val != null && type.isInstance(val) ? Optional.of(type.cast(val)) : Optional.empty();
        }
    }

    /**
     * Atomically releases a result-cache claim, but only if it is still held by the exact handle passed in.
     * Called when the guarded work fails so that a retry is not rejected as a duplicate.
     *
     * @return true if the claim was still owned by {@code expectedResult} and has now been released.
     */
    public boolean removeResult(String compositeKey, Object expectedResult) {
        Objects.requireNonNull(compositeKey, "compositeKey cannot be null");
        Objects.requireNonNull(expectedResult, "expectedResult cannot be null");
        synchronized (results) {
            if (results.get(compositeKey) != expectedResult) {
                return false;
            }
            results.remove(compositeKey);
            return true;
        }
    }

    @Override
    public void resetForTesting() {
        synchronized (seenKeys) {
            seenKeys.clear();
            expiryIndex.clear();
            expiryQueue.clear();
            operationsSinceSweep = 0;
        }
        synchronized (results) {
            results.clear();
        }
    }

    /**
     * Convenience factory for the completion handle stored by callers of
     * {@link #putResultIfAbsent(String, Object)}. Duplicate callers await the winner's completion
     * instead of repeating the work.
     */
    public static <T> CompletableFuture<T> newCompletion() {
        return new CompletableFuture<>();
    }

    /** Identity-compared on purpose: only the node currently indexed may detach the map entry. */
    private record ExpiryNode(String key, FingerprintEntry entry) {
        Instant recordedAt() {
            return entry.recordedAt();
        }
    }

    private record FingerprintEntry(String fingerprint, Instant recordedAt) {

        /**
         * Expiry predicate. Deliberately the same comparison the old sweep used, so a key becomes
         * invisible at exactly the instant it did before.
         */
        boolean isExpired(Instant now, Duration ttl) {
            return recordedAt.plus(ttl).isBefore(now);
        }
    }
}