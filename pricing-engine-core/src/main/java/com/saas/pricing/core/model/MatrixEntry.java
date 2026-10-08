package com.saas.pricing.core.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * An entry within a DimensionalMatrixModel.
 * Represents a price point or sub-model triggered when dimensional criteria match.
 * Supports exact matches, wildcards (*), numeric intervals [min, max], and specificity scoring.
 */
public record MatrixEntry(
    Map<String, String> dimensionValues,
    PricingModel model,
    int priority
) implements Serializable {

    public MatrixEntry {
        Objects.requireNonNull(dimensionValues, "dimensionValues cannot be null");
        Objects.requireNonNull(model, "model cannot be null");
        dimensionValues = Map.copyOf(dimensionValues);
    }

    public MatrixEntry(Map<String, String> dimensionValues, PricingModel model) {
        this(dimensionValues, model, 0);
    }

    public static MatrixEntry of(Map<String, String> dimensionValues, PricingModel model) {
        return new MatrixEntry(dimensionValues, model, 0);
    }

    public static MatrixEntry of(Map<String, String> dimensionValues, PricingModel model, int priority) {
        return new MatrixEntry(dimensionValues, model, priority);
    }

    public static MatrixEntry of(String dimKey, String dimVal, PricingModel model) {
        return new MatrixEntry(Map.of(dimKey, dimVal), model, 0);
    }

    public static MatrixEntry of(String dimKey, String dimVal, PricingModel model, int priority) {
        return new MatrixEntry(Map.of(dimKey, dimVal), model, priority);
    }

    public boolean matches(Map<String, ?> attributes) {
        if (dimensionValues.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> expected : dimensionValues.entrySet()) {
            String expectedVal = expected.getValue();
            // Wildcard matches anything
            if ("*".equals(expectedVal)) {
                continue;
            }

            Object actual = attributes != null ? attributes.get(expected.getKey()) : null;
            if (actual == null) {
                return false;
            }
            String actualStr = String.valueOf(actual).trim();

            // Range matching: e.g. [10, 50]
            if (expectedVal.startsWith("[") && expectedVal.endsWith("]") && expectedVal.contains(",")) {
                if (!matchesNumericRange(expectedVal, actualStr)) {
                    return false;
                }
            } else if (!expectedVal.equalsIgnoreCase(actualStr)) {
                return false;
            }
        }
        return true;
    }

    public int specificityScore(Map<String, ?> attributes) {
        int score = 0;
        for (Map.Entry<String, String> expected : dimensionValues.entrySet()) {
            String expectedVal = expected.getValue();
            if ("*".equals(expectedVal)) {
                score += 1;
            } else if (expectedVal.startsWith("[") && expectedVal.endsWith("]")) {
                score += 5;
            } else {
                score += 10;
            }
        }
        return score + (priority * 100);
    }

    private static boolean matchesNumericRange(String rangeStr, String valueStr) {
        try {
            String inside = rangeStr.substring(1, rangeStr.length() - 1).trim();
            String[] parts = inside.split(",");
            if (parts.length != 2) return false;
            BigDecimal min = new BigDecimal(parts[0].trim());
            BigDecimal max = new BigDecimal(parts[1].trim());
            BigDecimal val = new BigDecimal(valueStr.trim());
            return val.compareTo(min) >= 0 && val.compareTo(max) <= 0;
        } catch (Exception e) {
            return false;
        }
    }
}
