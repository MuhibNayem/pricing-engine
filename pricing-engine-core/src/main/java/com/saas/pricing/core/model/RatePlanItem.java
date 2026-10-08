package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines a billable rate plan component (e.g. seats, API calls, storage, base subscription).
 */
public record RatePlanItem(
    String itemCode,
    String metricName,
    PricingModel pricingModel,
    CurrencyUnit baseCurrency,
    Optional<BigDecimal> includedAllowance,
    boolean proratable,
    Map<String, String> metadata
) implements Serializable {

    public RatePlanItem {
        Objects.requireNonNull(itemCode, "itemCode cannot be null");
        Objects.requireNonNull(metricName, "metricName cannot be null");
        Objects.requireNonNull(pricingModel, "pricingModel cannot be null");
        Objects.requireNonNull(baseCurrency, "baseCurrency cannot be null");
        Objects.requireNonNull(includedAllowance, "includedAllowance cannot be null");
        Objects.requireNonNull(metadata, "metadata cannot be null");

        if (itemCode.isBlank()) throw new IllegalArgumentException("itemCode cannot be blank");
        metadata = Map.copyOf(metadata);
    }

    public static RatePlanItem of(String itemCode, String metricName, PricingModel pricingModel, CurrencyUnit currency) {
        return new RatePlanItem(itemCode, metricName, pricingModel, currency, Optional.empty(), false, Map.of());
    }

    public static RatePlanItem of(String itemCode, String metricName, PricingModel pricingModel, CurrencyUnit currency, BigDecimal allowance) {
        return new RatePlanItem(itemCode, metricName, pricingModel, currency, Optional.of(allowance), false, Map.of());
    }

    public static RatePlanItem proratable(String itemCode, String metricName, PricingModel pricingModel, CurrencyUnit currency) {
        return new RatePlanItem(itemCode, metricName, pricingModel, currency, Optional.empty(), true, Map.of());
    }

    public BigDecimal computeBillableQuantity(BigDecimal rawQuantity) {
        if (rawQuantity == null || rawQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (includedAllowance.isPresent()) {
            BigDecimal allowance = includedAllowance.get();
            BigDecimal billable = rawQuantity.subtract(allowance);
            return billable.compareTo(BigDecimal.ZERO) > 0 ? billable : BigDecimal.ZERO;
        }
        return rawQuantity;
    }
}
