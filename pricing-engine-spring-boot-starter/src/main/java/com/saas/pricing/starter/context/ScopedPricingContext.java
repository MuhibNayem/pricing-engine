package com.saas.pricing.starter.context;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Scoped Value context management for thread-safe multi-tenant and multi-customer pricing execution
 * across Java 25 virtual threads without ThreadLocal memory leaks or thread inheritance overhead.
 */
public final class ScopedPricingContext {

    public static final ScopedValue<TenantId> CURRENT_TENANT = ScopedValue.newInstance();
    public static final ScopedValue<CustomerId> CURRENT_CUSTOMER = ScopedValue.newInstance();
    public static final ScopedValue<String> CORRELATION_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> CALLER_IDENTITY = ScopedValue.newInstance();
    public static final ScopedValue<Instant> EVALUATION_TIME = ScopedValue.newInstance();

    private ScopedPricingContext() {}

    public static Optional<TenantId> currentTenant() {
        return CURRENT_TENANT.isBound() ? Optional.of(CURRENT_TENANT.get()) : Optional.empty();
    }

    public static Optional<CustomerId> currentCustomer() {
        return CURRENT_CUSTOMER.isBound() ? Optional.of(CURRENT_CUSTOMER.get()) : Optional.empty();
    }

    public static Optional<String> correlationId() {
        return CORRELATION_ID.isBound() ? Optional.of(CORRELATION_ID.get()) : Optional.empty();
    }

    public static Optional<String> callerIdentity() {
        return CALLER_IDENTITY.isBound() ? Optional.of(CALLER_IDENTITY.get()) : Optional.empty();
    }

    public static Optional<Instant> evaluationTime() {
        return EVALUATION_TIME.isBound() ? Optional.of(EVALUATION_TIME.get()) : Optional.empty();
    }

    public static <T> T runWithTenant(TenantId tenantId, String correlationId, Callable<T> task) throws Exception {
        return ScopedValue.where(CURRENT_TENANT, tenantId)
            .where(CORRELATION_ID, correlationId)
            .call(task::call);
    }

    public static <T> T runWithCustomer(TenantId tenantId, CustomerId customerId, String correlationId, Callable<T> task) throws Exception {
        return ScopedValue.where(CURRENT_TENANT, tenantId)
            .where(CURRENT_CUSTOMER, customerId)
            .where(CORRELATION_ID, correlationId)
            .call(task::call);
    }

    public static <T> T callWithContext(
        TenantId tenantId,
        CustomerId customerId,
        String correlationId,
        String callerIdentity,
        Instant evalTime,
        Callable<T> task
    ) throws Exception {
        var carrier = ScopedValue.where(CURRENT_TENANT, tenantId)
            .where(CORRELATION_ID, correlationId);

        if (customerId != null) {
            carrier = carrier.where(CURRENT_CUSTOMER, customerId);
        }
        if (callerIdentity != null) {
            carrier = carrier.where(CALLER_IDENTITY, callerIdentity);
        }
        if (evalTime != null) {
            carrier = carrier.where(EVALUATION_TIME, evalTime);
        }

        return carrier.call(task::call);
    }
}
