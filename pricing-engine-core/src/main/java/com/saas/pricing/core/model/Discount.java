package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines a discount or promotion applicable to a line item or total invoice.
 */
public record Discount(
    String code,
    DiscountType type,
    BigDecimal value,
    DiscountScope scope,
    Optional<String> targetItemCode,
    DiscountStackingRule stackingRule,
    int priority,
    Optional<Money> maxCap,
    Optional<Instant> validUntil,
    Optional<Money> minSpendRequirement,
    Optional<CurrencyUnit> fixedCurrency
) implements Serializable {

    public Discount {
        Objects.requireNonNull(code, "code cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(scope, "scope cannot be null");
        Objects.requireNonNull(targetItemCode, "targetItemCode cannot be null");
        Objects.requireNonNull(stackingRule, "stackingRule cannot be null");
        Objects.requireNonNull(maxCap, "maxCap cannot be null");
        Objects.requireNonNull(validUntil, "validUntil cannot be null");
        Objects.requireNonNull(minSpendRequirement, "minSpendRequirement cannot be null");
        Objects.requireNonNull(fixedCurrency, "fixedCurrency cannot be null");

        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Discount value cannot be negative");
        }
        if (type == DiscountType.PERCENTAGE && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percentage discount cannot exceed 100%");
        }
        // A FIXED_AMOUNT without a fixedCurrency is the legacy constructor: the amount is assumed to
        // be in the currency being discounted. The factories always set it, and the engine refuses a
        // mismatch rather than silently reinterpreting the amount.
        if (type != DiscountType.FIXED_AMOUNT && fixedCurrency.isPresent()) {
            throw new IllegalArgumentException(
                "Only a FIXED_AMOUNT discount may carry a fixed currency");
        }
        if (type == DiscountType.FREE_UNITS && scope == DiscountScope.INVOICE_TOTAL) {
            throw new IllegalArgumentException(
                "FREE_UNITS applies to a line item's quantity, not an invoice total");
        }
        if (type == DiscountType.FREE_UNITS && targetItemCode.isEmpty()) {
            throw new IllegalArgumentException("A FREE_UNITS discount must name its target item");
        }
    }

    /** Backward-compatible constructor: no minimum-spend requirement and no fixed currency. */
    public Discount(
        String code,
        DiscountType type,
        BigDecimal value,
        DiscountScope scope,
        Optional<String> targetItemCode,
        DiscountStackingRule stackingRule,
        int priority,
        Optional<Money> maxCap,
        Optional<Instant> validUntil
    ) {
        this(code, type, value, scope, targetItemCode, stackingRule, priority, maxCap, validUntil,
            Optional.empty(), Optional.empty());
    }

    public static Discount percentage(String code, BigDecimal percentage) {
        return new Discount(code, DiscountType.PERCENTAGE, percentage, DiscountScope.INVOICE_TOTAL,
            Optional.empty(), DiscountStackingRule.WATERFALL, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Discount percentage(String code, BigDecimal percentage, DiscountStackingRule rule) {
        return new Discount(code, DiscountType.PERCENTAGE, percentage, DiscountScope.INVOICE_TOTAL,
            Optional.empty(), rule, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Discount percentageItem(String code, BigDecimal percentage, String targetItemCode) {
        return new Discount(code, DiscountType.PERCENTAGE, percentage, DiscountScope.LINE_ITEM,
            Optional.of(targetItemCode), DiscountStackingRule.WATERFALL, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Discount percentageItem(String code, BigDecimal percentage, String targetItemCode, DiscountStackingRule rule) {
        return new Discount(code, DiscountType.PERCENTAGE, percentage, DiscountScope.LINE_ITEM,
            Optional.of(targetItemCode), rule, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Discount fixedAmount(String code, Money amount) {
        return new Discount(code, DiscountType.FIXED_AMOUNT, amount.amount(), DiscountScope.INVOICE_TOTAL,
            Optional.empty(), DiscountStackingRule.WATERFALL, 0, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(amount.currency()));
    }

    public static Discount fixedAmount(String code, Money amount, DiscountStackingRule rule) {
        return new Discount(code, DiscountType.FIXED_AMOUNT, amount.amount(), DiscountScope.INVOICE_TOTAL,
            Optional.empty(), rule, 0, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(amount.currency()));
    }

    public static Discount fixedAmountItem(String code, Money amount, String targetItemCode) {
        return new Discount(code, DiscountType.FIXED_AMOUNT, amount.amount(), DiscountScope.LINE_ITEM,
            Optional.of(targetItemCode), DiscountStackingRule.WATERFALL, 0, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(amount.currency()));
    }

    /** Free units: {@code value} is the number of units removed from the charged quantity. */
    public static Discount freeUnitsItem(String code, BigDecimal units, String targetItemCode) {
        return new Discount(code, DiscountType.FREE_UNITS, units, DiscountScope.LINE_ITEM,
            Optional.of(targetItemCode), DiscountStackingRule.WATERFALL, 0, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    public boolean isExpired(Instant timestamp) {
        return validUntil.map(until -> timestamp.isAfter(until)).orElse(false);
    }

    public boolean isApplicable(Money spend, Instant timestamp) {
        if (isExpired(timestamp)) {
            return false;
        }
        return minSpendRequirement.map(min -> spend.compareTo(min) >= 0).orElse(true);
    }
}
