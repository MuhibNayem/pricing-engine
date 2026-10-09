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
        if (includedAllowance.isPresent()) {
            if (includedAllowance.get().compareTo(BigDecimal.ZERO) < 0) {
                // A negative allowance increases the billable quantity: the opposite of an allowance.
                throw new IllegalArgumentException(
                    "includedAllowance cannot be negative for item '" + itemCode + "'");
            }
            if (pricingModel instanceof PricingModel.HybridModel) {
                // Both would be subtracted from the quantity (here and inside the hybrid model), so
                // a 5-unit allowance declared twice gives 10 free units and undercharges every
                // customer. The hybrid model's own includedUnits is the single source of truth.
                throw new IllegalArgumentException(
                    "Item '" + itemCode + "' declares both a RatePlanItem allowance and a "
                        + "HybridModel includedUnits; declare the allowance in the hybrid model only");
            }
        }
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
