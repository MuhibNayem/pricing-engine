package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable, high-precision monetary value for enterprise financial calculations.
 * Always maintains exact fractional precision and enforces currency isolation.
 */
public record Money(BigDecimal amount, CurrencyUnit currency) implements Comparable<Money>, Serializable {

    public static final int CALCULATION_SCALE = 8;
    public static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_EVEN;
    public static final MathContext MATH_CONTEXT = new MathContext(24, DEFAULT_ROUNDING_MODE);

    public Money {
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(currency, "Currency cannot be null");
    }

    public static Money of(BigDecimal amount, CurrencyUnit currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amountStr, CurrencyUnit currency) {
        return new Money(new BigDecimal(amountStr), currency);
    }

    public static Money of(long amount, CurrencyUnit currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money of(double amount, CurrencyUnit currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money zero(CurrencyUnit currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        verifySameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money minus(Money other) {
        verifySameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money times(BigDecimal multiplier) {
        Objects.requireNonNull(multiplier, "Multiplier cannot be null");
        return new Money(this.amount.multiply(multiplier, MATH_CONTEXT), this.currency);
    }

    public Money times(long multiplier) {
        return times(BigDecimal.valueOf(multiplier));
    }

    public Money times(double multiplier) {
        return times(BigDecimal.valueOf(multiplier));
    }

    public Money dividedBy(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "Divisor cannot be null");
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Cannot divide Money by zero");
        }
        return new Money(this.amount.divide(divisor, CALCULATION_SCALE, DEFAULT_ROUNDING_MODE), this.currency);
    }

    public Money dividedBy(long divisor) {
        return dividedBy(BigDecimal.valueOf(divisor));
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.currency);
    }

    public Money abs() {
        return new Money(this.amount.abs(), this.currency);
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public Money roundToCurrency() {
        return roundTo(this.currency.defaultFractionDigits(), DEFAULT_ROUNDING_MODE);
    }

    public Money roundTo(int scale, RoundingMode roundingMode) {
        return new Money(this.amount.setScale(scale, roundingMode), this.currency);
    }

    public Money min(Money other) {
        verifySameCurrency(other);
        return this.compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        verifySameCurrency(other);
        return this.compareTo(other) >= 0 ? this : other;
    }

    public boolean isGreaterThan(Money other) {
        return this.compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return this.compareTo(other) < 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        return this.compareTo(other) >= 0;
    }

    public boolean isLessThanOrEqualTo(Money other) {
        return this.compareTo(other) <= 0;
    }

    private void verifySameCurrency(Money other) {
        Objects.requireNonNull(other, "Other Money cannot be null");
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                "Currency mismatch: Cannot compute between %s and %s".formatted(this.currency.code(), other.currency.code())
            );
        }
    }

    @Override
    public int compareTo(Money other) {
        verifySameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money other)) return false;
        return this.currency.equals(other.currency) && this.amount.compareTo(other.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.amount.stripTrailingZeros(), this.currency);
    }

    @Override
    public String toString() {
        return "%s %s".formatted(this.amount.stripTrailingZeros().toPlainString(), this.currency.code());
    }
}
