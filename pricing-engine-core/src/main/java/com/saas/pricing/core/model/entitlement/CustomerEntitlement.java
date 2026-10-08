package com.saas.pricing.core.model.entitlement;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Entitlement state for a specific customer.
 */
public record CustomerEntitlement(
    String entitlementId,
    TenantId tenantId,
    CustomerId customerId,
    PlanCode planCode,
    String featureKey,
    FeatureType type,
    boolean booleanValue,
    Optional<BigDecimal> quotaLimit,
    BigDecimal currentUsage,
    boolean isHardLimit,
    Instant effectiveFrom,
    Optional<Instant> effectiveTo
) implements Serializable {

    public CustomerEntitlement {
        Objects.requireNonNull(entitlementId, "entitlementId cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(planCode, "planCode cannot be null");
        Objects.requireNonNull(featureKey, "featureKey cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(quotaLimit, "quotaLimit cannot be null");
        Objects.requireNonNull(currentUsage, "currentUsage cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(effectiveTo, "effectiveTo cannot be null");
    }

    public static CustomerEntitlement booleanEntitlement(
        String id, TenantId tenant, CustomerId customer, PlanCode plan, String featureKey, boolean value, Instant from
    ) {
        return new CustomerEntitlement(
            id, tenant, customer, plan, featureKey, FeatureType.BOOLEAN, value,
            Optional.empty(), BigDecimal.ZERO, true, from, Optional.empty()
        );
    }

    public static CustomerEntitlement metered(
        String id, TenantId tenant, CustomerId customer, PlanCode plan, String featureKey,
        BigDecimal limit, BigDecimal currentUsage, boolean isHardLimit, Instant from
    ) {
        return new CustomerEntitlement(
            id, tenant, customer, plan, featureKey, FeatureType.METERED_RECURRING, true,
            Optional.of(limit), currentUsage, isHardLimit, from, Optional.empty()
        );
    }

    public boolean isEffectiveAt(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        if (timestamp.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo.map(to -> !timestamp.isAfter(to)).orElse(true);
    }

    public CustomerEntitlement recordUsage(BigDecimal usageDelta) {
        return new CustomerEntitlement(
            entitlementId, tenantId, customerId, planCode, featureKey, type,
            booleanValue, quotaLimit, currentUsage.add(usageDelta), isHardLimit, effectiveFrom, effectiveTo
        );
    }
}
