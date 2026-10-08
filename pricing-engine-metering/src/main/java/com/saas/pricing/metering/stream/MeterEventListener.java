package com.saas.pricing.metering.stream;

import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterEvent;

/**
 * Listener interface notified when meter events are ingested.
 */
@FunctionalInterface
public interface MeterEventListener {

    /**
     * Callback invoked after an event has been evaluated and ingested.
     */
    void onEventIngested(MeterEvent event, IngestionResult result);
}
