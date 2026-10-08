package com.saas.pricing.core.model;

import java.io.Serializable;
import java.util.Objects;

public record CustomerId(String value) implements Serializable {
    public CustomerId {
        Objects.requireNonNull(value, "CustomerId value cannot be null");
        if (value.isBlank()) throw new IllegalArgumentException("CustomerId cannot be blank");
    }
    public static CustomerId of(String value) {
        return new CustomerId(value);
    }
}
