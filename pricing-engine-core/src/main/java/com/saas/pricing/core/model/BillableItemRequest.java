package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * A requested line item to price (e.g. quantity of seats, storage used, API calls).
 */
public record BillableItemRequest(
    String itemCode,
    BigDecimal quantity,
    Map<String, Object> attributes
) implements Serializable {

    public BillableItemRequest {
        Objects.requireNonNull(itemCode, "itemCode cannot be null");
        Objects.requireNonNull(quantity, "quantity cannot be null");
        Objects.requireNonNull(attributes, "attributes cannot be null");

        if (quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative: " + quantity);
        }
        attributes = Map.copyOf(attributes);
    }

    public static BillableItemRequest of(String itemCode, BigDecimal quantity) {
        return new BillableItemRequest(itemCode, quantity, Map.of());
    }

    public static BillableItemRequest of(String itemCode, long quantity) {
        return new BillableItemRequest(itemCode, BigDecimal.valueOf(quantity), Map.of());
    }

    public static BillableItemRequest of(String itemCode, double quantity) {
        return new BillableItemRequest(itemCode, BigDecimal.valueOf(quantity), Map.of());
    }

    public static BillableItemRequest of(String itemCode, BigDecimal quantity, Map<String, Object> attributes) {
        return new BillableItemRequest(itemCode, quantity, attributes);
    }
}
