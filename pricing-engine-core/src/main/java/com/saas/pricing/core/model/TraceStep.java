package com.saas.pricing.core.model;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

/**
 * An individual audit step inside a calculation trace.
 */
public record TraceStep(
    String stepName,
    String description,
    Map<String, Object> details
) implements Serializable {

    public TraceStep {
        Objects.requireNonNull(stepName, "stepName cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        Objects.requireNonNull(details, "details cannot be null");
        details = Map.copyOf(details);
    }

    public static TraceStep of(String stepName, String description, Map<String, Object> details) {
        return new TraceStep(stepName, description, details);
    }

    public static TraceStep of(String stepName, String description) {
        return new TraceStep(stepName, description, Map.of());
    }
}
