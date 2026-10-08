package com.saas.pricing.core.spi;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.entitlement.CustomerEntitlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SPI for managing customer feature entitlements and real-time usage counters.
 */
public interface EntitlementRepository {

    /**
     * Finds active customer entitlement for a specific feature key at a given timestamp.
     */
    Optional<CustomerEntitlement> findEntitlement(TenantId tenantId, CustomerId customerId, String featureKey, Instant timestamp);

    /**
     * Finds all active entitlements for a customer.
     */
    List<CustomerEntitlement> findAllEntitlements(TenantId tenantId, CustomerId customerId, Instant timestamp);

    /**
     * Saves or updates a customer entitlement.
     */
    void saveEntitlement(CustomerEntitlement entitlement);

    /**
     * Atomically records usage consumption on a customer entitlement.
     */
    void recordUsage(TenantId tenantId, CustomerId customerId, String featureKey, BigDecimal usageDelta);
}
