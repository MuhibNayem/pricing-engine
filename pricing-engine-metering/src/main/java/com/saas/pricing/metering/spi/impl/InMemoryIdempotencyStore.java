package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.IdempotencyStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory implementation of IdempotencyStore.
 *
 * <p>Two independent maps are maintained:</p>
 * <ul>
 *   <li>{@code seenKeys} — per-event ingestion idempotency, {@code (tenant, idempotencyKey) -> recordedInstant}.
 *       Claimed via atomic {@link ConcurrentMap#putIfAbsent(Object, Object)} and released via
 *       atomic compare-and-remove so a failed save does not burn the key.</li>
 *   <li>{@code results} — per-charge result cache used by window-level rating idempotency. The claim
 *       is again a single atomic {@code putIfAbsent}; the winning caller stores a completion handle
 *       that duplicate callers await, so a duplicate never performs a second charge.</li>
 * </ul>
 *
 * <p>Both maps are unbounded. Callers that key on high-cardinality input should apply a TTL/eviction
 * policy in front of this store in production.</p>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, Instant> seenKeys = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> results = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    private String compositeKey(TenantId tenantId, String idempotencyKey) {
        return tenantId.value() + "::" + idempotencyKey;
    }

    @Override
    public boolean checkAndRecord(TenantId tenantId, String idempotencyKey, Instant eventTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Instant time = eventTime != null ? eventTime : clock.instant();

        return seenKeys.putIfAbsent(compositeKey(tenantId, idempotencyKey), time) == null;
    }

    @Override
    public boolean isDuplicate(TenantId tenantId, String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        return seenKeys.containsKey(compositeKey(tenantId, idempotencyKey));
    }

    /**
     * Atomically releases a recorded key, but only if it still holds exactly the value written by the
     * matching {@link #checkAndRecord} call. A concurrent re-record by another caller is never removed.
     */
    @Override
    public boolean remove(TenantId tenantId, String idempotencyKey, Instant recordedTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(recordedTime, "recordedTime cannot be null");
        return seenKeys.remove(compositeKey(tenantId, idempotencyKey), recordedTime);
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
        return results.putIfAbsent(compositeKey, result) == null;
    }

    /**
     * Returns the handle claimed for a composite key, if any.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> findResult(String compositeKey, Class<T> type) {
        Objects.requireNonNull(compositeKey, "compositeKey cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        return Optional.ofNullable((T) results.get(compositeKey));
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
        return results.remove(compositeKey, expectedResult);
    }

    public void clear() {
        seenKeys.clear();
        results.clear();
    }

    /**
     * Convenience factory for the completion handle stored by callers of
     * {@link #putResultIfAbsent(String, Object)}. Duplicate callers await the winner's completion
     * instead of repeating the work.
     */
    public static <T> CompletableFuture<T> newCompletion() {
        return new CompletableFuture<>();
    }
}