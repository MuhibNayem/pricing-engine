package com.saas.pricing.core.model;

import java.io.Serializable;
import java.util.Objects;

public record TenantId(String value) implements Serializable {
    public TenantId {
        Objects.requireNonNull(value, "TenantId value cannot be null");
        if (value.isBlank()) throw new IllegalArgumentException("TenantId cannot be blank");
    }
    public static TenantId of(String value) {
        return new TenantId(value);
    }
}
