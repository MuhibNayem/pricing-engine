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

    /**
     * Parses a monetary amount.
     *
     * <p>Accepts either form:
     * <ul>
     *   <li>a bare numeric — {@code "100.00"}, the amount in the supplied currency; or</li>
     *   <li>the rendered form this class emits — {@code "100.00 USD"} — where the trailing code
     *       must equal the supplied currency.</li>
     * </ul>
     *
     * <p>Accepting the rendered form makes {@code of(m.toString(), m.currency())} round-trip, which
     * it previously did not: {@link #toString()} emits the currency code but this method used to
     * reject it, so anything that serialised a {@code Money} through {@code toString()} had no
     * supported way to read it back.
     *
     * @throws IllegalArgumentException if the text is not a valid amount, or carries a currency
     *                                  code that disagrees with {@code currency}
     */
    public static Money of(String amountStr, CurrencyUnit currency) {
        Objects.requireNonNull(amountStr, "Amount string cannot be null");
        Objects.requireNonNull(currency, "Currency cannot be null");

        String trimmed = amountStr.strip();
        int split = trimmed.lastIndexOf(' ');
        if (split < 0) {
            return new Money(new BigDecimal(trimmed), currency);
        }

        String numeric = trimmed.substring(0, split).strip();
        String code = trimmed.substring(split + 1).strip();
        if (!code.equals(currency.code())) {
            throw new IllegalArgumentException(
                "Amount '" + amountStr + "' is denominated in " + code + ", not " + currency.code());
        }
        return new Money(new BigDecimal(numeric), currency);
    }

    /**
     * Parses the rendered form {@code "<amount> <CODE>"} and takes the currency from the text.
     *
     * <p>The inverse of {@link #toString()}: {@code parse(m.toString())} reproduces {@code m}.
     *
     * @throws IllegalArgumentException if the text is not a recognised amount
     */
    public static Money parse(String rendered) {
        Objects.requireNonNull(rendered, "Rendered amount cannot be null");

        String trimmed = rendered.strip();
        int split = trimmed.lastIndexOf(' ');
        if (split < 0) {
            throw new IllegalArgumentException(
                "Rendered amount '" + rendered + "' carries no currency code");
        }
        String numeric = trimmed.substring(0, split).strip();
        String code = trimmed.substring(split + 1).strip();
        return new Money(new BigDecimal(numeric), CurrencyUnit.of(code));
    }

    /**
     * Rounds to the currency's own scale, the boundary where an amount becomes documentable.
     *
     * <p>The constructor deliberately accepts more precision than the currency can represent,
     * because intermediate arithmetic needs somewhere to hold the extra digits — {@link
     * #CALCULATION_SCALE} is 8. This is the method that closes that gap on the way out, using the
     * same {@link #DEFAULT_ROUNDING_MODE} as the rest of the engine so a document never disagrees
     * with its own arithmetic.
     *
     * <p>Call this when producing a value for a customer, an invoice, or an API response. Do not
     * call it mid-calculation.
     */
    public Money roundedToCurrencyScale() {
        int scale = currency.defaultFractionDigits();
        if (scale < 0) {
            return this;
        }
        BigDecimal rounded = amount.setScale(scale, DEFAULT_ROUNDING_MODE);
        return rounded.equals(amount) ? this : new Money(rounded, currency);
    }

    /**
     * Whether this amount carries precision its currency cannot represent.
     *
     * <p>Legal to hold — see {@link #roundedToCurrencyScale()} — but it must not reach a document.
     */
    public boolean exceedsCurrencyScale() {
        return amount.scale() > currency.defaultFractionDigits()
            && amount.stripTrailingZeros().scale() > currency.defaultFractionDigits();
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
        return dividedBy(divisor, CALCULATION_SCALE);
    }

    /**
     * Divides at an explicit scale, for callers whose precision policy differs from the default
     * calculation scale (for example minor-unit conversion for a currency with 0 or 3 digits).
     */
    public Money dividedBy(BigDecimal divisor, int scale) {
        Objects.requireNonNull(divisor, "Divisor cannot be null");
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException("Cannot divide Money by zero");
        }
        return new Money(this.amount.divide(divisor, scale, DEFAULT_ROUNDING_MODE), this.currency);
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
