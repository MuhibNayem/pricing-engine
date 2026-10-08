package com.saas.pricing.metering.model;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable raw usage meter event ingested by the pricing engine.
 */
public record MeterEvent(
    String eventId,
    String idempotencyKey,
    TenantId tenantId,
    Optional<CustomerId> customerId,
    String meterCode,
    BigDecimal value,
    Instant timestamp,
    Map<String, Object> properties
) implements Serializable {

    public MeterEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");
        Objects.requireNonNull(properties, "properties cannot be null");

        if (meterCode.isBlank()) {
            throw new IllegalArgumentException("meterCode cannot be blank");
        }
        properties = Map.copyOf(properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MeterEvent of(String meterCode, BigDecimal value, TenantId tenantId, Instant timestamp) {
        return builder()
            .meterCode(meterCode)
            .value(value)
            .tenantId(tenantId)
            .timestamp(timestamp)
            .build();
    }

    public static MeterEvent of(String meterCode, double value, String tenantId, Instant timestamp) {
        return builder()
            .meterCode(meterCode)
            .value(BigDecimal.valueOf(value))
            .tenantId(tenantId)
            .timestamp(timestamp)
            .build();
    }

    public static MeterEvent of(String meterCode, BigDecimal value, TenantId tenantId, CustomerId customerId, Instant timestamp) {
        return builder()
            .meterCode(meterCode)
            .value(value)
            .tenantId(tenantId)
            .customerId(customerId)
            .timestamp(timestamp)
            .build();
    }

    public static final class Builder {
        private String eventId;
        private String idempotencyKey;
        private TenantId tenantId;
        private CustomerId customerId;
        private String meterCode;
        private BigDecimal value = BigDecimal.ONE;
        private Instant timestamp;
        private final Map<String, Object> properties = new HashMap<>();

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder tenantId(TenantId tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = TenantId.of(tenantId);
            return this;
        }

        public Builder customerId(CustomerId customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder customerId(String customerId) {
            this.customerId = customerId != null ? CustomerId.of(customerId) : null;
            return this;
        }

        public Builder meterCode(String meterCode) {
            this.meterCode = meterCode;
            return this;
        }

        public Builder value(BigDecimal value) {
            this.value = value;
            return this;
        }

        public Builder value(long value) {
            this.value = BigDecimal.valueOf(value);
            return this;
        }

        public Builder value(double value) {
            this.value = BigDecimal.valueOf(value);
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder property(String key, Object val) {
            this.properties.put(key, val);
            return this;
        }

        public Builder properties(Map<String, Object> properties) {
            if (properties != null) {
                this.properties.putAll(properties);
            }
            return this;
        }

        public MeterEvent build() {
            String id = (eventId != null && !eventId.isBlank()) ? eventId : UUID.randomUUID().toString();
            String key = (idempotencyKey != null && !idempotencyKey.isBlank()) ? idempotencyKey : id;
            Instant ts = (timestamp != null) ? timestamp : Instant.now();
            Objects.requireNonNull(tenantId, "tenantId is required");
            Objects.requireNonNull(meterCode, "meterCode is required");

            return new MeterEvent(
                id,
                key,
                tenantId,
                Optional.ofNullable(customerId),
                meterCode,
                value,
                ts,
                properties
            );
        }
    }
}
