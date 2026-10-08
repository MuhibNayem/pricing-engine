package com.saas.pricing.core.model.wallet;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.Money;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable prepaid credits wallet for a specific customer.
 */
public record Wallet(
    String walletId,
    TenantId tenantId,
    CustomerId customerId,
    CurrencyUnit currency,
    List<CreditGrant> grants
) implements Serializable {

    public Wallet {
        Objects.requireNonNull(walletId, "walletId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(grants, "grants cannot be null");
        grants = List.copyOf(grants);
    }

    public static Wallet of(String walletId, TenantId tenantId, CustomerId customerId, CurrencyUnit currency, List<CreditGrant> grants) {
        return new Wallet(walletId, tenantId, customerId, currency, grants);
    }

    public BigDecimal totalRemainingCredits(Instant timestamp) {
        return grants.stream()
            .filter(g -> g.isActiveAt(timestamp))
            .map(CreditGrant::remainingCredits)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Money totalAvailableMoneyValue(Instant timestamp) {
        Money total = Money.zero(currency);
        for (CreditGrant grant : grants) {
            if (grant.isActiveAt(timestamp)) {
                total = total.plus(grant.moneyValueOf(grant.remainingCredits(), currency));
            }
        }
        return total;
    }

    public Wallet withGrants(List<CreditGrant> updatedGrants) {
        return new Wallet(walletId, tenantId, customerId, currency, updatedGrants);
    }
}
