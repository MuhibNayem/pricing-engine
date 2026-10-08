package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.entitlement.CustomerEntitlement;
import com.saas.pricing.core.model.entitlement.EntitlementDecision;
import com.saas.pricing.core.model.entitlement.FeatureType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Enterprise entitlement and quota verification engine.
 * Validates feature access, evaluates hard vs soft quotas, and computes remaining allowances.
 */
public final class EntitlementVerifier {

    public EntitlementDecision verify(
        CustomerEntitlement entitlement,
        BigDecimal requestedUnits,
        Instant evalTime
    ) {
        Objects.requireNonNull(entitlement, "entitlement cannot be null");
        Objects.requireNonNull(requestedUnits, "requestedUnits cannot be null");
        Objects.requireNonNull(evalTime, "evalTime cannot be null");

        if (!entitlement.isEffectiveAt(evalTime)) {
            return EntitlementDecision.denied(
                entitlement.featureKey(),
                entitlement.type(),
                "Entitlement is not active at " + evalTime
            );
        }

        if (entitlement.type() == FeatureType.BOOLEAN) {
            if (entitlement.booleanValue()) {
                return EntitlementDecision.allowed(
                    entitlement.featureKey(),
                    entitlement.type(),
                    "Feature entitlement enabled"
                );
            } else {
                return EntitlementDecision.denied(
                    entitlement.featureKey(),
                    entitlement.type(),
                    "Feature entitlement disabled for customer"
                );
            }
        }

        // Metered entitlement
        if (entitlement.quotaLimit().isEmpty()) {
            // Unlimited metered allowance
            return new EntitlementDecision(
                true,
                entitlement.featureKey(),
                entitlement.type(),
                requestedUnits,
                entitlement.currentUsage(),
                Optional.empty(),
                BigDecimal.valueOf(Double.MAX_VALUE),
                false,
                BigDecimal.ZERO,
                "Unlimited quota allowance"
            );
        }

        BigDecimal limit = entitlement.quotaLimit().get();
        BigDecimal newTotal = entitlement.currentUsage().add(requestedUnits);

        if (newTotal.compareTo(limit) <= 0) {
            BigDecimal remaining = limit.subtract(newTotal);
            return new EntitlementDecision(
                true,
                entitlement.featureKey(),
                entitlement.type(),
                requestedUnits,
                entitlement.currentUsage(),
                Optional.of(limit),
                remaining,
                false,
                BigDecimal.ZERO,
                "Usage within allowance (remaining: %s)".formatted(remaining)
            );
        }

        // Quota exceeded
        BigDecimal overage = newTotal.subtract(limit);
        if (entitlement.isHardLimit()) {
            return new EntitlementDecision(
                false,
                entitlement.featureKey(),
                entitlement.type(),
                requestedUnits,
                entitlement.currentUsage(),
                Optional.of(limit),
                BigDecimal.ZERO,
                true,
                overage,
                "Hard quota limit of %s exceeded by %s units".formatted(limit, overage)
            );
        } else {
            return new EntitlementDecision(
                true,
                entitlement.featureKey(),
                entitlement.type(),
                requestedUnits,
                entitlement.currentUsage(),
                Optional.of(limit),
                BigDecimal.ZERO,
                true,
                overage,
                "Soft quota limit exceeded: overage of %s units permitted".formatted(overage)
            );
        }
    }
}
