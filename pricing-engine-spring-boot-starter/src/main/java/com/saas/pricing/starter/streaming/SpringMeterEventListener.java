package com.saas.pricing.starter.streaming;

import com.saas.pricing.metering.stream.AsyncRatingTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.util.Objects;

/**
 * Spring event listener handling ingested meter events.
 */
public class SpringMeterEventListener {

    private static final Logger log = LoggerFactory.getLogger(SpringMeterEventListener.class);

    private final AsyncRatingTriggerService asyncRatingTriggerService;

    private final java.util.List<java.util.function.Consumer<MeterIngestedApplicationEvent>> customHandlers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public SpringMeterEventListener(AsyncRatingTriggerService asyncRatingTriggerService) {
        this.asyncRatingTriggerService = asyncRatingTriggerService;
    }

    public AsyncRatingTriggerService getAsyncRatingTriggerService() {
        return asyncRatingTriggerService;
    }

    public void addHandler(java.util.function.Consumer<MeterIngestedApplicationEvent> handler) {
        if (handler != null) {
            customHandlers.add(handler);
        }
    }

    @EventListener
    @Async
    public void onMeterEventIngested(MeterIngestedApplicationEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        log.debug("Meter event processed: id={}, tenant={}, meter={}, accepted={}",
            event.getMeterEvent().eventId(),
            event.getMeterEvent().tenantId().value(),
            event.getMeterEvent().meterCode(),
            event.getIngestionResult().isAccepted()
        );

        for (var handler : customHandlers) {
            try {
                handler.accept(event);
            } catch (Exception e) {
                log.warn("Error executing custom handler for meter event {}", event.getMeterEvent().eventId(), e);
            }
        }
    }
}
