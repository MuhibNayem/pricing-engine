package com.saas.pricing.core.model.wallet;

import com.saas.pricing.core.model.BillingCadence;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Enterprise contract spend commitment (minimum spend guarantee).
 * If the customer's rated usage is lower than minimumAmount, a true-up adjustment charge is applied.
 */
public record SpendCommitment(
    String commitmentId,
    TenantId tenantId,
    CustomerId customerId,
    Money minimumAmount,
    BillingCadence cadence,
    Instant effectiveFrom,
    Optional<Instant> effectiveTo
) implements Serializable {

    public SpendCommitment {
        Objects.requireNonNull(commitmentId, "commitmentId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(minimumAmount, "minimumAmount cannot be null");
        Objects.requireNonNull(cadence, "cadence cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(effectiveTo, "effectiveTo cannot be null");
    }

    public static SpendCommitment of(
        String commitmentId,
        TenantId tenantId,
        CustomerId customerId,
        Money minimumAmount,
        BillingCadence cadence,
        Instant effectiveFrom
    ) {
        return new SpendCommitment(commitmentId, tenantId, customerId, minimumAmount, cadence, effectiveFrom, Optional.empty());
    }

    public static SpendCommitment of(
        String commitmentId,
        TenantId tenantId,
        CustomerId customerId,
        Money minimumAmount,
        BillingCadence cadence,
        Instant effectiveFrom,
        Instant effectiveTo
    ) {
        return new SpendCommitment(commitmentId, tenantId, customerId, minimumAmount, cadence, effectiveFrom, Optional.of(effectiveTo));
    }

    public boolean isEffectiveAt(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (timestamp.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo.map(to -> !timestamp.isAfter(to)).orElse(true);
    }

    /**
     * Calculates the true-up charge required if actual billed spend is below the minimum commitment.
     */
    public Money calculateTrueUp(Money actualSpend) {
        Objects.requireNonNull(actualSpend, "actualSpend cannot be null");
        if (actualSpend.compareTo(minimumAmount) < 0) {
            return minimumAmount.minus(actualSpend);
        }
        return Money.zero(minimumAmount.currency());
    }
}
