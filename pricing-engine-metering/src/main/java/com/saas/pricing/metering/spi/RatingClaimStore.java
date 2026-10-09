package com.saas.pricing.metering.spi;

import com.saas.pricing.core.model.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable memory of what a rating window has already been charged.
 *
 * <p>Without it, "has this window been billed?" lived only in an in-JVM completion cache: a late
 * event that enlarged the recomputation was discarded by the window idempotency key, and a restart
 * re-charged from zero. The claim lets the service charge only the difference and survive restarts.</p>
 */
public interface RatingClaimStore {

    /**
     * What a window has been charged so far.
     *
     * <p>{@code amount} is normalised with {@code stripTrailingZeros}: the value round-trips through
     * a {@code NUMERIC(24,8)} column as {@code 10.00000000}, and record equality is scale-sensitive,
     * so without normalisation a claim read back would never equal the one written.</p>
     *
     * @param amount    total rated for the window that has already been drawn down
     * @param currency  ISO code of that amount
     * @param chargedAt when the claim was last written
     */
    record Charged(BigDecimal amount, String currency, Instant chargedAt) {
        public Charged {
            Objects.requireNonNull(amount, "amount cannot be null");
            Objects.requireNonNull(currency, "currency cannot be null");
            Objects.requireNonNull(chargedAt, "chargedAt cannot be null");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("A charged amount cannot be negative: " + amount);
            }
            amount = amount.stripTrailingZeros();
        }
    }

    /** The current claim for a window, if it has ever been charged. */
    Optional<Charged> find(TenantId tenantId, String claimKey);

    /** Writes the claim unconditionally (used after a successful charge). */
    void record(TenantId tenantId, String claimKey, Charged charged);

    /**
     * Atomically replaces {@code expected} with {@code updated}.
     *
     * @return true when this caller performed the replacement. False means another node moved the
     *         claim first; the caller must re-read and recompute its delta rather than charge.
     */
    boolean compareAndSet(TenantId tenantId, String claimKey,
                          Optional<Charged> expected, Charged updated);

    /**
     * Removes the claim if and only if it still holds {@code expected}. Used to hand a just-taken
     * first claim back when the drawdown it authorised failed, so a retry can charge.
     */
    boolean remove(TenantId tenantId, String claimKey, Charged expected);
}
