package com.saas.pricing.starter.streaming;

import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterEvent;
import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * Spring ApplicationEvent published whenever a meter event is ingested.
 */
public class MeterIngestedApplicationEvent extends ApplicationEvent {

    private final MeterEvent meterEvent;
    private final IngestionResult ingestionResult;

    public MeterIngestedApplicationEvent(Object source, MeterEvent meterEvent, IngestionResult ingestionResult) {
        super(source);
        this.meterEvent = Objects.requireNonNull(meterEvent, "meterEvent cannot be null");
        this.ingestionResult = Objects.requireNonNull(ingestionResult, "ingestionResult cannot be null");
    }

    public MeterEvent getMeterEvent() {
        return meterEvent;
    }

    public IngestionResult getIngestionResult() {
        return ingestionResult;
    }
}
