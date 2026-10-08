package com.saas.pricing.metering.stream;

import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Publisher interface for sending meter events into the metering pipeline or event stream.
 */
public interface MeterEventPublisher {

    /**
     * Publishes a meter event asynchronously and returns the ingestion result future.
     */
    CompletableFuture<IngestionResult> publish(MeterEvent event);
}
