package com.saas.pricing.core.model;

import java.io.Serializable;
import java.util.Objects;

public record PlanCode(String value) implements Serializable {
    public PlanCode {
        Objects.requireNonNull(value, "PlanCode value cannot be null");
        if (value.isBlank()) throw new IllegalArgumentException("PlanCode cannot be blank");
    }
    public static PlanCode of(String value) {
        return new PlanCode(value);
    }
}
