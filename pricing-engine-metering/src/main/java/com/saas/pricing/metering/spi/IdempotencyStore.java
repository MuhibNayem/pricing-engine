package com.saas.pricing.metering.spi;

import com.saas.pricing.core.model.TenantId;

import java.time.Instant;

/**
 * SPI for atomic event deduplication and idempotency verification.
 *
 * <p>A claim is content-aware. Two events carrying the same idempotency key but different content
 * are not "a retry" - they are a caller error that, if silently answered DUPLICATE, drops an event
 * the caller believes was recorded. {@link Claim#CONFLICT} exists so that error is visible.</p>
 */
public interface IdempotencyStore {

    /** Outcome of claiming an idempotency key. */
    enum Claim {
        /** This caller now owns the key; the event is new. */
        CLAIMED,
        /** The same key was already claimed with identical content: a safe retry. */
        DUPLICATE,
        /** The key was already claimed with different content: the event must be rejected. */
        CONFLICT
    }

    /**
     * Atomically claims the key for the given content fingerprint.
     *
     * @param fingerprint stable digest of the event's content (meter, customer, value, timestamp,
     *                    properties); an empty string disables content comparison
     * @param eventTime event timestamp, or {@code null} to use the store's clock
     */
    Claim claim(TenantId tenantId, String idempotencyKey, String fingerprint, Instant eventTime);

    /**
     * Convenience for callers with no content to compare. Returns true only when the key is new.
     */
    default boolean checkAndRecord(TenantId tenantId, String idempotencyKey, Instant eventTime) {
        return claim(tenantId, idempotencyKey, "", eventTime) == Claim.CLAIMED;
    }

    /**
     * Checks if an idempotency key was already seen.
     */
    boolean isDuplicate(TenantId tenantId, String idempotencyKey);

    /**
     * Releases a previously recorded idempotency key, but only if the store still holds the exact
     * value that was written by the corresponding claim. Used to roll a key back when the work it
     * guarded failed, so a legitimate retry is not rejected as a duplicate.
     *
     * <p>This is intentionally a {@code default} no-op: an adapter that cannot release keys safely
     * keeps its previous (conservative) behaviour rather than failing to compile. Stores that support
     * rollback must override it with an atomic compare-and-remove.</p>
     *
     * @return true if the key was held with the given value and has now been released.
     */
    default boolean remove(TenantId tenantId, String idempotencyKey, Instant recordedTime) {
        return false;
    }
}
