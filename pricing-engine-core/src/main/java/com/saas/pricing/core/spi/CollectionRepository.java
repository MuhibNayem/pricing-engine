package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.collection.PaymentAttempt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Append-only storage for collection attempts.
 *
 * <p>Duplicate protection is the reason this is not just a list: a collection agent that times out
 * and re-sends must be recognisable as the same charge. {@link #record(PaymentAttempt)} therefore
 * returns whether the attempt was newly recorded, and the implementation relies on the derived
 * attempt id plus a uniqueness constraint rather than on the caller checking first.
 */
public interface CollectionRepository {

    /**
     * Records an attempt.
     *
     * @return true if newly recorded; false if this exact attempt was already on file (a retry),
     *         in which case nothing is written and the customer is not charged again
     * @throws IllegalArgumentException if the attempt id exists with different content
     */
    boolean record(PaymentAttempt attempt);

    /** Attempts already recorded against an invoice, in attempt order. */
    List<PaymentAttempt> findAttempts(TenantId tenantId, String invoiceId);

    /** The most recent attempt, if any. */
    Optional<PaymentAttempt> findLatest(TenantId tenantId, String invoiceId);

    /** Every attempt whose next retry is due at or before {@code at}, across all invoices. */
    List<PaymentAttempt> findDue(Instant at);
}