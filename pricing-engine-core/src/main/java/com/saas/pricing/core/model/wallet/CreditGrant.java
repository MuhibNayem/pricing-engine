package com.saas.pricing.core.model.wallet;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.Money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable representation of a credit grant within a customer's wallet.
 * Supports credit-to-money conversion rates, burn priorities, and expiration dates.
 */
public record CreditGrant(
    String grantId,
    String walletId,
    String name,
    GrantType type,
    BigDecimal initialCredits,
    BigDecimal remainingCredits,
    BigDecimal creditToMoneyRate,
    int priority,
    Instant effectiveFrom,
    Optional<Instant> expiresAt
) implements Serializable {

    public CreditGrant {
        Objects.requireNonNull(grantId, "grantId cannot be null");
        Objects.requireNonNull(walletId, "walletId cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(initialCredits, "initialCredits cannot be null");
        Objects.requireNonNull(remainingCredits, "remainingCredits cannot be null");
        Objects.requireNonNull(creditToMoneyRate, "creditToMoneyRate cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");

        if (initialCredits.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("initialCredits cannot be negative");
        }
        if (remainingCredits.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("remainingCredits cannot be negative");
        }
        if (creditToMoneyRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("creditToMoneyRate must be strictly positive");
        }
    }

    public static CreditGrant prepaid(String grantId, String walletId, String name, BigDecimal credits, BigDecimal rate, Instant effectiveFrom) {
        return new CreditGrant(grantId, walletId, name, GrantType.PREPAID, credits, credits, rate, 10, effectiveFrom, Optional.empty());
    }

    public static CreditGrant promotional(String grantId, String walletId, String name, BigDecimal credits, BigDecimal rate, Instant effectiveFrom, Instant expiresAt) {
        return new CreditGrant(grantId, walletId, name, GrantType.PROMOTIONAL, credits, credits, rate, 1, effectiveFrom, Optional.of(expiresAt));
    }

    public boolean isActiveAt(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (timestamp.isBefore(effectiveFrom)) {
            return false;
        }
        if (expiresAt.isPresent() && timestamp.isAfter(expiresAt.get())) {
            return false;
        }
        return remainingCredits.compareTo(BigDecimal.ZERO) > 0;
    }

    public Money moneyValueOf(BigDecimal credits, CurrencyUnit currency) {
        BigDecimal monetaryAmount = credits.multiply(creditToMoneyRate);
        return Money.of(monetaryAmount, currency);
    }

    public BigDecimal creditsForMoney(Money money) {
        return money.amount().divide(creditToMoneyRate, 8, RoundingMode.HALF_EVEN);
    }

    public CreditGrant deduct(BigDecimal creditsToDeduct) {
        Objects.requireNonNull(creditsToDeduct, "creditsToDeduct cannot be null");
        if (creditsToDeduct.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("creditsToDeduct cannot be negative");
        }
        BigDecimal newRemaining = remainingCredits.subtract(creditsToDeduct);
        if (newRemaining.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Deduct amount exceeds remaining credits on grant " + grantId);
        }
        return new CreditGrant(
            grantId, walletId, name, type, initialCredits, newRemaining, creditToMoneyRate, priority, effectiveFrom, expiresAt
        );
    }
}
