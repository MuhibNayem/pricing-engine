package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.IdempotencyStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    /** Retention for an ingestion key. Longer than any plausible transport retry. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    /** Maximum retained ingestion keys and in-flight charge handles. */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final Map<String, FingerprintEntry> seenKeys;
    private final Map<String, Object> results;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;

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
            purgeExpired(now);
            FingerprintEntry existing = seenKeys.get(key);
            if (existing == null) {
                seenKeys.put(key, new FingerprintEntry(content, eventTime != null ? eventTime : now));
                return Claim.CLAIMED;
            }
            return existing.fingerprint().equals(content) ? Claim.DUPLICATE : Claim.CONFLICT;
        }
    }

    @Override
    public boolean isDuplicate(TenantId tenantId, String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        synchronized (seenKeys) {
            purgeExpired(clock.instant());
            return seenKeys.containsKey(compositeKey(tenantId, idempotencyKey));
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
        synchronized (seenKeys) {
            FingerprintEntry existing = seenKeys.get(compositeKey(tenantId, idempotencyKey));
            if (existing == null || !existing.recordedAt().equals(recordedTime)) {
                return false;
            }
            seenKeys.remove(compositeKey(tenantId, idempotencyKey));
            return true;
        }
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<String, FingerprintEntry>> iterator = seenKeys.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().recordedAt().plus(ttl).isBefore(now)) {
                iterator.remove();
            }
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

    public void clear() {
        synchronized (seenKeys) {
            seenKeys.clear();
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

    private record FingerprintEntry(String fingerprint, Instant recordedAt) {
    }
}
