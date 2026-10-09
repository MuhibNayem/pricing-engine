package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.TenantId;

/**
 * Hands out gapless, tenant-scoped numeric sequences.
 *
 * <h2>The claim must be atomic</h2>
 * Sequence allocation is the one place where check-then-increment is unacceptable: two concurrent
 * finalizations that both read "next = 41" and both write 42 produce two documents with the same
 * number, and a duplicate invoice number is the single most audit-visible defect in billing. It
 * also silently breaks the tax-year continuity that {@code ACCOUNT_SEQUENTIAL} exists to prove.
 *
 * <h2>Sequences are per (tenant, key), never global</h2>
 * A tenant must not be able to consume another tenant's numbers, and a roll-back under one tenant
 * must not disturb another's.
 */
public interface SequenceAllocator {

    /**
     * Allocates the next value for {@code sequenceKey} within {@code tenantId}.
     *
     * <p>Atomic, and the returned value is unique for the lifetime of the key unless
     * {@link #restore} is called for it.
     *
     * @param tenantId    owning tenant
     * @param sequenceKey which series to draw from; {@code "ACCOUNT"} or {@code "CUSTOMER:<id>"}
     * @param startAt     the value to use if this series has never been allocated. Must be at
     *                    least 1. Exists so a tenant migrating from another system can resume where
     *                    that system stopped instead of restarting at 1.
     * @return the allocated value, never previously returned for this key
     */
    long nextValue(TenantId tenantId, String sequenceKey, long startAt);

    /**
     * Returns a value to the series so the next allocation reuses it.
     *
     * <p>Only valid for a value that was allocated and never issued — i.e. a finalization that then
     * failed. Compare-and-set on the value: if the series has already moved on, this is a no-op,
     * because rewinding a counter past numbers that were handed out would re-issue a number that a
     * customer already holds.
     *
     * @return true if the value was returned to the series
     */
    boolean restore(TenantId tenantId, String sequenceKey, long value);

    /** The value most recently allocated for this key, without consuming a new one. */
    long peek(TenantId tenantId, String sequenceKey, long startAt);
}