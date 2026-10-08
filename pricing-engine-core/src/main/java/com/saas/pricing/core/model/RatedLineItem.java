package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Result of rating an individual billable item, containing complete pricing breakdown.
 */
public record RatedLineItem(
    String itemCode,
    BigDecimal rawQuantity,
    BigDecimal billableQuantity,
    Money grossAmount,
    Money discountAmount,
    Money netAmount,
    Money taxAmount,
    Money finalAmount,
    List<TraceStep> traceSteps
) implements Serializable {

    public RatedLineItem {
        Objects.requireNonNull(itemCode, "itemCode cannot be null");
        Objects.requireNonNull(rawQuantity, "rawQuantity cannot be null");
        Objects.requireNonNull(billableQuantity, "billableQuantity cannot be null");
        Objects.requireNonNull(grossAmount, "grossAmount cannot be null");
        Objects.requireNonNull(discountAmount, "discountAmount cannot be null");
        Objects.requireNonNull(netAmount, "netAmount cannot be null");
        Objects.requireNonNull(taxAmount, "taxAmount cannot be null");
        Objects.requireNonNull(finalAmount, "finalAmount cannot be null");
        Objects.requireNonNull(traceSteps, "traceSteps cannot be null");

        traceSteps = List.copyOf(traceSteps);
    }
}
