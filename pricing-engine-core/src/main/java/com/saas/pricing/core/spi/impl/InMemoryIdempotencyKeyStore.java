package com.saas.pricing.core.spi.impl;

import com.saas.pricing.core.spi.ResettableForTesting;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.idempotency.IdempotencyDecision;
import com.saas.pricing.core.model.idempotency.IdempotencyRecord;
import com.saas.pricing.core.spi.IdempotencyKeyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency-key store using a single atomic claim operation.
 *
 * <p>The claim uses {@link Map#compute} rather than check-then-insert, because the latter is the race
 * this whole facility exists to prevent: two concurrent requests would both see the key absent and
 * both proceed to issue an invoice. {@code compute} holds the key's bin lock for the whole
 * read-decide-write, so the reclaim of an expired entry is just as atomic as the initial claim —
 * a {@code replace} followed by {@code putIfAbsent} is not, and lets two racers both believe they
 * reclaimed a stale entry.
 */
public class InMemoryIdempotencyKeyStore implements IdempotencyKeyStore, ResettableForTesting {

    /** Tenant-scoped identity of a key. */
    private record Scope(TenantId tenantId, String key) {
    }

    private final Map<Scope, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public IdempotencyDecision decide(TenantId tenantId, String key, String fingerprint,
                                      Duration ttl, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(fingerprint, "fingerprint cannot be null");
        Objects.requireNonNull(ttl, "ttl cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }

        IdempotencyRecord claim = IdempotencyRecord.claim(tenantId, key, fingerprint, now, ttl);
        Scope scope = new Scope(tenantId, key);

        // Atomic for this key: absent or expired -> install our claim and return it; otherwise keep
        // the incumbent untouched. The identity check below tells us which happened.
        IdempotencyRecord result = records.compute(scope, (ignored, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                return claim;
            }
            return existing;
        });

        if (result == claim) {
            return IdempotencyDecision.proceed(claim);
        }
        if (!result.matches(fingerprint)) {
            // Same key, different payload: replaying would tell the caller it created something it
            // did not.
            return IdempotencyDecision.conflict(result);
        }
        if (result.status() == IdempotencyRecord.Status.COMPLETED) {
            return IdempotencyDecision.replay(result);
        }
        return IdempotencyDecision.inFlight(result);
    }

    @Override
    public void complete(TenantId tenantId, IdempotencyRecord claim, int httpStatus, String responseBody) {
        Objects.requireNonNull(claim, "claim cannot be null");
        Scope scope = new Scope(tenantId, claim.key());
        records.computeIfPresent(scope, (ignored, existing) -> {
            // Fence on the claim. If the key was released or reclaimed while this request ran, the
            // incumbent is no longer ours and overwriting it would destroy a newer execution's result.
            if (existing.recordedAt().isBefore(claim.recordedAt())
                || existing.status() == IdempotencyRecord.Status.COMPLETED) {
                return existing;
            }
            return new IdempotencyRecord(existing.tenantId(), existing.key(), existing.fingerprint(),
                IdempotencyRecord.Status.COMPLETED, httpStatus, responseBody == null ? "" : responseBody,
                existing.recordedAt(), existing.expiresAt());
        });
    }

    @Override
    public void release(TenantId tenantId, IdempotencyRecord claim) {
        Objects.requireNonNull(claim, "claim cannot be null");
        records.computeIfPresent(new Scope(tenantId, claim.key()), (ignored, existing) ->
            existing.recordedAt().isAfter(claim.recordedAt()) ? existing : null);
    }

    @Override
    public void resetForTesting() {
        records.clear();
    }
}