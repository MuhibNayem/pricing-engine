package com.saas.pricing.metering.spi;

import com.saas.pricing.core.model.TenantId;

import java.util.concurrent.locks.Lock;

/**
 * Mutual exclusion per {@code (tenant, meter)} for the aggregation cache-aside path.
 *
 * <p>In-process deployments use a striped in-JVM registry. Deployments that share a database can
 * plug in an advisory-lock implementation so two nodes cannot both aggregate, invalidate and re-save
 * the same window - the race the lock exists to prevent is not confined to one JVM.</p>
 */
public interface MeterLockRegistry {

    /**
     * Returns the lock guarding the given tenant+meter. Callers must not rely on the lock being
     * exclusive to this exact key — an implementation may stripe several keys onto one lock.
     */
    Lock lockFor(TenantId tenantId, String meterCode);
}
