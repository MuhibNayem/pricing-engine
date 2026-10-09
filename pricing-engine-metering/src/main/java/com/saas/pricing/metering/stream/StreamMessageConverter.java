package com.saas.pricing.metering.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterEvent;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Message converter for streaming event consumers (Kafka, RabbitMQ, Spring Cloud Stream, etc.).
 * Converts between raw messages (JSON strings, byte payloads, Maps) and strongly typed MeterEvent domain instances.
 */
public class StreamMessageConverter {

    private final ObjectMapper objectMapper;

    public StreamMessageConverter() {
        this(new ObjectMapper());
    }

    public StreamMessageConverter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    /**
     * Converts a raw map into a MeterEvent.
     */
    @SuppressWarnings("unchecked")
    public MeterEvent fromMap(Map<String, Object> map) {
        Objects.requireNonNull(map, "map cannot be null");

        String tenantIdStr = Objects.requireNonNull(map.get("tenantId"), "tenantId is required").toString();
        String meterCode = Objects.requireNonNull(map.get("meterCode"), "meterCode is required").toString();

        String eventId = map.get("eventId") != null ? map.get("eventId").toString() : null;
        String idempotencyKey = map.get("idempotencyKey") != null ? map.get("idempotencyKey").toString() : null;
        String customerIdStr = map.get("customerId") != null ? map.get("customerId").toString() : null;

        BigDecimal value;
        Object valObj = map.get("value");
        if (valObj == null) {
            // A missing quantity used to be silently billed as ONE. The wrong quantity is worse
            // than a rejected message: it prices, invoices and settles without anyone noticing.
            throw new IllegalArgumentException(
                "Stream message for meter '" + meterCode + "' has no 'value'; the quantity is "
                    + "required and is never assumed");
        } else if (valObj instanceof Number n) {
            value = new BigDecimal(n.toString());
        } else if (valObj instanceof String s && !s.isBlank()) {
            value = new BigDecimal(s);
        } else {
            throw new IllegalArgumentException(
                "Stream message for meter '" + meterCode + "' has an unusable 'value': " + valObj);
        }

        Object tsObj = map.get("timestamp");
        if (tsObj == null || (tsObj instanceof String s && s.isBlank())) {
            // Defaulting to now() assigned usage to whatever window the message happened to arrive
            // in, bypassing the injected clock and the caller's own watermarks.
            throw new IllegalArgumentException(
                "Stream message for meter '" + meterCode + "' has no 'timestamp'; the event time is "
                    + "required because it decides which billing window the usage belongs to");
        }
        Instant timestamp;
        if (tsObj instanceof String s) {
            timestamp = Instant.parse(s);
        } else if (tsObj instanceof Number n) {
            timestamp = Instant.ofEpochMilli(n.longValue());
        } else {
            throw new IllegalArgumentException(
                "Stream message for meter '" + meterCode + "' has an unusable 'timestamp': " + tsObj);
        }

        Map<String, Object> properties = Map.of();
        Object propObj = map.get("properties");
        if (propObj instanceof Map<?, ?> m) {
            properties = (Map<String, Object>) m;
        }

        MeterEvent.Builder builder = MeterEvent.builder()
            .tenantId(TenantId.of(tenantIdStr))
            .meterCode(meterCode)
            .value(value)
            .timestamp(timestamp)
            .properties(properties);

        if (eventId != null && !eventId.isBlank()) {
            builder.eventId(eventId);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.idempotencyKey(idempotencyKey);
        }
        if (customerIdStr != null && !customerIdStr.isBlank()) {
            builder.customerId(CustomerId.of(customerIdStr));
        }

        return builder.build();
    }

    /**
     * Converts a MeterEvent into a standardized Map representation.
     */
    public Map<String, Object> toMap(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        Map<String, Object> map = new HashMap<>();
        map.put("eventId", event.eventId());
        map.put("idempotencyKey", event.idempotencyKey());
        map.put("tenantId", event.tenantId().value());
        event.customerId().ifPresent(c -> map.put("customerId", c.value()));
        map.put("meterCode", event.meterCode());
        map.put("value", event.value());
        map.put("timestamp", event.timestamp().toString());
        map.put("properties", event.properties());
        return map;
    }

    /**
     * Parses a JSON string message into a MeterEvent.
     */
    @SuppressWarnings("unchecked")
    public MeterEvent fromJson(String json) {
        Objects.requireNonNull(json, "json cannot be null");
        if (json.isBlank()) {
            throw new IllegalArgumentException("JSON string cannot be blank");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return fromMap(map);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse JSON stream message to MeterEvent", e);
        }
    }

    /**
     * Serializes a MeterEvent to a JSON string.
     */
    public String toJson(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        try {
            return objectMapper.writeValueAsString(toMap(event));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize MeterEvent to JSON", e);
        }
    }

    /**
     * Parses raw byte payload (e.g. from Kafka/RabbitMQ consumer) into a MeterEvent.
     */
    public MeterEvent fromBytes(byte[] payload) {
        Objects.requireNonNull(payload, "payload cannot be null");
        return fromJson(new String(payload, StandardCharsets.UTF_8));
    }

    /**
     * Serializes a MeterEvent into a UTF-8 byte array.
     */
    public byte[] toBytes(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        return toJson(event).getBytes(StandardCharsets.UTF_8);
    }
}
