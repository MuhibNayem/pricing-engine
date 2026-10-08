package com.saas.pricing.core.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import java.util.Optional;

/**
 * Complete evaluation result returned by the PricingEngine.
 */
public record PricingResult(
    String calculationId,
    TenantId tenantId,
    Optional<CustomerId> customerId,
    PlanCode planCode,
    Instant evaluatedAt,
    CurrencyUnit currency,
    Money totalGross,
    Money totalDiscount,
    Money totalNet,
    Money totalTax,
    Money finalTotal,
    List<RatedLineItem> lineItems,
    EvaluationTrace trace
) implements Serializable {

    public PricingResult {
        Objects.requireNonNull(calculationId, "calculationId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(totalGross, "totalGross cannot be null");
        Objects.requireNonNull(totalDiscount, "totalDiscount cannot be null");
        Objects.requireNonNull(totalNet, "totalNet cannot be null");
        Objects.requireNonNull(totalTax, "totalTax cannot be null");
        Objects.requireNonNull(finalTotal, "finalTotal cannot be null");
        Objects.requireNonNull(lineItems, "lineItems cannot be null");
        Objects.requireNonNull(trace, "trace cannot be null");

        lineItems = List.copyOf(lineItems);
    }

    public PricingResult(
        String calculationId,
        TenantId tenantId,
        PlanCode planCode,
        Instant evaluatedAt,
        CurrencyUnit currency,
        Money totalGross,
        Money totalDiscount,
        Money totalNet,
        Money totalTax,
        Money finalTotal,
        List<RatedLineItem> lineItems,
        EvaluationTrace trace
    ) {
        this(calculationId, tenantId, Optional.empty(), planCode, evaluatedAt, currency, totalGross, totalDiscount, totalNet, totalTax, finalTotal, lineItems, trace);
    }
}
