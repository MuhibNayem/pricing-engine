package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Represents a tax rate applied to a line item or total.
 */
public record TaxRate(
    String taxCode,
    BigDecimal percentage,
    String jurisdiction
) implements Serializable {

    public TaxRate {
        Objects.requireNonNull(taxCode, "taxCode cannot be null");
        Objects.requireNonNull(percentage, "percentage cannot be null");
        Objects.requireNonNull(jurisdiction, "jurisdiction cannot be null");

        if (percentage.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("tax percentage cannot be negative");
        }
    }

    public static TaxRate of(String taxCode, BigDecimal percentage, String jurisdiction) {
        return new TaxRate(taxCode, percentage, jurisdiction);
    }
}
