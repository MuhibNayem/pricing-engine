package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.hierarchy.ContractOverride;

import java.time.Instant;
import java.util.Optional;

/**
 * SPI for storing and retrieving customer-specific negotiated contract overrides.
 */
public interface ContractOverrideRepository {

    /**
     * Finds active contract override effective for the given customer at the given timestamp.
     */
    Optional<ContractOverride> findEffectiveOverride(TenantId tenantId, CustomerId customerId, PlanCode planCode, Instant effectiveTime);

    /**
     * Bi-temporal lookup: finds active contract override effective at effectiveTime as known at systemTime.
     */
    default Optional<ContractOverride> findBiTemporalOverride(TenantId tenantId, CustomerId customerId, PlanCode planCode, Instant effectiveTime, Instant systemTime) {
        return findEffectiveOverride(tenantId, customerId, planCode, effectiveTime)
            .filter(o -> o.isRecordValidAt(systemTime));
    }

    /**
     * Saves or updates a contract override.
     */
    void save(ContractOverride override);
}
