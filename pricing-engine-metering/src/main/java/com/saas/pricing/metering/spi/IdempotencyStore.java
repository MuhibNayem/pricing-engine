package com.saas.pricing.metering.spi;

import com.saas.pricing.core.model.TenantId;

import java.time.Instant;

/**
 * SPI for atomic event deduplication and idempotency verification.
 */
public interface IdempotencyStore {

    /**
     * Atomically checks if the idempotency key was already recorded for this tenant.
     * If not previously recorded, records it and returns true.
     * If already recorded (duplicate), returns false.
     */
    boolean checkAndRecord(TenantId tenantId, String idempotencyKey, Instant eventTime);

    /**
     * Checks if an idempotency key was already seen.
     */
    boolean isDuplicate(TenantId tenantId, String idempotencyKey);

    /**
     * Releases a previously recorded idempotency key, but only if the store still holds the exact
     * value that was written by the corresponding {@link #checkAndRecord(TenantId, String, Instant)}
     * call. Used to roll a key back when the work it guarded failed, so a legitimate retry is not
     * rejected as a duplicate.
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
