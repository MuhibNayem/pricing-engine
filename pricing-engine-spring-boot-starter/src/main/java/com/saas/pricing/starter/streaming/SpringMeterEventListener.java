package com.saas.pricing.starter.streaming;

import com.saas.pricing.metering.stream.AsyncRatingTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.Objects;
import java.util.Optional;

/**
 * Spring event listener handling ingested meter events.
 *
 * <p>Handlers run synchronously on the publishing thread. The previous {@code @Async} annotation
 * was inert (no {@code @EnableAsync} anywhere) and merely misdescribed the behaviour; a host that
 * wants asynchronous handlers registers one that dispatches, or enables async itself. The rating
 * trigger is exposed through {@link #getAsyncRatingTriggerService()} for a handler to invoke with
 * the plan and window the event does not carry.
 */
public class SpringMeterEventListener {

    private static final Logger log = LoggerFactory.getLogger(SpringMeterEventListener.class);

    private final AsyncRatingTriggerService asyncRatingTriggerService;

    private final java.util.List<java.util.function.Consumer<MeterIngestedApplicationEvent>> customHandlers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public SpringMeterEventListener() {
        this(null);
    }

    public SpringMeterEventListener(AsyncRatingTriggerService asyncRatingTriggerService) {
        this.asyncRatingTriggerService = asyncRatingTriggerService;
    }

    /**
     * Exposes the {@link AsyncRatingTriggerService} for event handlers to invoke.
     *
     * @return an {@link Optional} containing the service if async rating is enabled and configured,
     *         or {@link Optional#empty()} if async rating is disabled.
     */
    public Optional<AsyncRatingTriggerService> getAsyncRatingTriggerService() {
        return Optional.ofNullable(asyncRatingTriggerService);
    }

    public void addHandler(java.util.function.Consumer<MeterIngestedApplicationEvent> handler) {
        if (handler != null) {
            customHandlers.add(handler);
        }
    }

    @EventListener
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
