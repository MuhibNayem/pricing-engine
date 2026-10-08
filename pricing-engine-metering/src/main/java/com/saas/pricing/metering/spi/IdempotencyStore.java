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
}
