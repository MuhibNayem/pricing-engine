package com.saas.pricing.metering.stream;

import com.saas.pricing.metering.model.MeterEvent;

import java.util.List;

/**
 * Consumer interface for ingesting raw meter events from message streams (Kafka, RabbitMQ, SQS, etc.).
 */
public interface MeterEventConsumer {

    /**
     * Consumes and processes a single meter event.
     */
    void consume(MeterEvent event);

    /**
     * Consumes and processes a batch of meter events.
     */
    default void consumeBatch(List<MeterEvent> events) {
        if (events != null) {
            events.forEach(this::consume);
        }
    }

    /**
     * Consumes and processes a raw JSON message using the given converter.
     */
    default void consumeJson(String json, StreamMessageConverter converter) {
        if (json != null && converter != null) {
            consume(converter.fromJson(json));
        }
    }

    /**
     * Consumes and processes a raw Map message using the given converter.
     */
    default void consumeMap(java.util.Map<String, Object> map, StreamMessageConverter converter) {
        if (map != null && converter != null) {
            consume(converter.fromMap(map));
        }
    }

    /**
     * Consumes and processes raw bytes (e.g. from Kafka/RabbitMQ) using the given converter.
     */
    default void consumeBytes(byte[] payload, StreamMessageConverter converter) {
        if (payload != null && converter != null) {
            consume(converter.fromBytes(payload));
        }
    }
}
