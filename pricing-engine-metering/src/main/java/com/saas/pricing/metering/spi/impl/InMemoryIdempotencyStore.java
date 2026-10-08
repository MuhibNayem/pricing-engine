package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.IdempotencyStore;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory implementation of IdempotencyStore.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, Instant> seenKeys = new ConcurrentHashMap<>();

    private String compositeKey(TenantId tenantId, String idempotencyKey) {
        return tenantId.value() + "::" + idempotencyKey;
    }

    @Override
    public boolean checkAndRecord(TenantId tenantId, String idempotencyKey, Instant eventTime) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Instant time = eventTime != null ? eventTime : Instant.now();

        return seenKeys.putIfAbsent(compositeKey(tenantId, idempotencyKey), time) == null;
    }

    @Override
    public boolean isDuplicate(TenantId tenantId, String idempotencyKey) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        return seenKeys.containsKey(compositeKey(tenantId, idempotencyKey));
    }

    public void clear() {
        seenKeys.clear();
    }
}
