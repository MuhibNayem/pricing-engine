package com.saas.pricing.core.model.entitlement;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of an entitlement and quota verification check.
 */
public record EntitlementDecision(
    boolean allowed,
    String featureKey,
    FeatureType type,
    BigDecimal requestedQuantity,
    BigDecimal currentUsage,
    Optional<BigDecimal> quotaLimit,
    BigDecimal remainingQuota,
    boolean isOverage,
    BigDecimal overageQuantity,
    String reason
) implements Serializable {

    public EntitlementDecision {
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(requestedQuantity, "requestedQuantity cannot be null");
        Objects.requireNonNull(currentUsage, "currentUsage cannot be null");
        Objects.requireNonNull(quotaLimit, "quotaLimit cannot be null");
        Objects.requireNonNull(remainingQuota, "remainingQuota cannot be null");
        Objects.requireNonNull(overageQuantity, "overageQuantity cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
    }

    public static EntitlementDecision allowed(String featureKey, FeatureType type, String reason) {
        return new EntitlementDecision(true, featureKey, type, BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty(), BigDecimal.ZERO, false, BigDecimal.ZERO, reason);
    }

    public static EntitlementDecision denied(String featureKey, FeatureType type, String reason) {
        return new EntitlementDecision(false, featureKey, type, BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty(), BigDecimal.ZERO, false, BigDecimal.ZERO, reason);
    }
}
