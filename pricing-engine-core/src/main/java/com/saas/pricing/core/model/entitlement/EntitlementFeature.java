package com.saas.pricing.core.model.entitlement;

import java.io.Serializable;
import java.util.Objects;

/**
 * Definition of an entitlement feature in the product catalog.
 */
public record EntitlementFeature(
    String featureKey,
    String displayName,
    FeatureType type,
    String description
) implements Serializable {

    public EntitlementFeature {
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(displayName, "displayName cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
    }

    public static EntitlementFeature booleanFeature(String featureKey, String displayName, String description) {
        return new EntitlementFeature(featureKey, displayName, FeatureType.BOOLEAN, description);
    }

    public static EntitlementFeature meteredRecurring(String featureKey, String displayName, String description) {
        return new EntitlementFeature(featureKey, displayName, FeatureType.METERED_RECURRING, description);
    }
}
