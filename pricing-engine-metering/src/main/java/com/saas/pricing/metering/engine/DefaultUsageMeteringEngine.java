package com.saas.pricing.metering.engine;

import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.AggregationType;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.MeterDefinition;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.metering.spi.IdempotencyStore;
import com.saas.pricing.metering.spi.MeterAggregationRepository;
import com.saas.pricing.metering.spi.MeterDefinitionRepository;
import com.saas.pricing.metering.spi.MeterEventRepository;
import com.saas.pricing.metering.spi.impl.InMemoryIdempotencyStore;
import com.saas.pricing.metering.spi.impl.InMemoryMeterAggregationRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterDefinitionRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterEventRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enterprise implementation of the UsageMeteringEngine.
 * Implements strict event deduplication, out-of-order event handling with watermarks,
 * window-based multi-model aggregation, and billable item rating generation.
 */
public class DefaultUsageMeteringEngine implements UsageMeteringEngine {

    private final IdempotencyStore idempotencyStore;
    private final MeterEventRepository eventRepository;
    private final MeterAggregationRepository aggregationRepository;
    private final MeterDefinitionRepository definitionRepository;
    private final Optional<Duration> allowedLateness;

    public DefaultUsageMeteringEngine() {
        this(
            new InMemoryIdempotencyStore(),
            new InMemoryMeterEventRepository(),
            new InMemoryMeterAggregationRepository(),
            new InMemoryMeterDefinitionRepository(),
            Optional.empty()
        );
    }

    public DefaultUsageMeteringEngine(
        IdempotencyStore idempotencyStore,
        MeterEventRepository eventRepository,
        MeterAggregationRepository aggregationRepository,
        MeterDefinitionRepository definitionRepository,
        Optional<Duration> allowedLateness
    ) {
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore cannot be null");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository cannot be null");
        this.aggregationRepository = Objects.requireNonNull(aggregationRepository, "aggregationRepository cannot be null");
        this.definitionRepository = Objects.requireNonNull(definitionRepository, "definitionRepository cannot be null");
        this.allowedLateness = Objects.requireNonNull(allowedLateness, "allowedLateness cannot be null");
    }

    @Override
    public IngestionResult ingest(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        // 1. Out-of-order Lateness / Watermark Check (must check before recording idempotency)
        if (allowedLateness.isPresent()) {
            Instant oldestAllowed = Instant.now().minus(allowedLateness.get());
            if (event.timestamp().isBefore(oldestAllowed)) {
                return IngestionResult.rejectedLate(
                    event.eventId(),
                    event.idempotencyKey(),
                    "Event timestamp %s is older than maximum allowed lateness threshold (%s)".formatted(
                        event.timestamp(), oldestAllowed
                    )
                );
            }
        }

        // 2. Idempotency Check
        boolean isNew = idempotencyStore.checkAndRecord(event.tenantId(), event.idempotencyKey(), event.timestamp());
        if (!isNew) {
            return IngestionResult.duplicate(event.eventId(), event.idempotencyKey());
        }

        // 3. Persist Event
        boolean saved = eventRepository.saveEvent(event);
        if (!saved) {
            return IngestionResult.duplicate(event.eventId(), event.idempotencyKey());
        }

        // 4. Invalidate any existing cached aggregation covering this event timestamp
        aggregationRepository.invalidateForEvent(event.tenantId(), event.customerId(), event.meterCode(), event.timestamp());

        return IngestionResult.accepted(event.eventId(), event.idempotencyKey());
    }

    @Override
    public List<IngestionResult> ingestBatch(List<MeterEvent> events) {
        Objects.requireNonNull(events, "events cannot be null");
        List<IngestionResult> results = new ArrayList<>(events.size());
        for (MeterEvent event : events) {
            results.add(ingest(event));
        }
        return results;
    }

    @Override
    public MeterAggregation aggregate(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(meterCode, "meterCode cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        // Check cached aggregation first
        Optional<MeterAggregation> cached = aggregationRepository.findAggregation(tenantId, customerId, meterCode, window);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Resolve aggregation rule
        MeterDefinition definition = definitionRepository.findDefinition(meterCode)
            .orElseGet(() -> MeterDefinition.sum(meterCode, "Default SUM aggregation"));

        AggregationType aggregationType = definition.aggregationType();

        // Query raw events in interval [startTime, endTime)
        List<MeterEvent> events = eventRepository.findEvents(tenantId, customerId, meterCode, window.startTime(), window.endTime());

        if (events.isEmpty()) {
            MeterAggregation empty = MeterAggregation.empty(tenantId, customerId, meterCode, window, aggregationType);
            aggregationRepository.saveAggregation(empty);
            return empty;
        }

        // Deterministic aggregation logic across all models
        BigDecimal aggregatedValue = switch (aggregationType) {
            case SUM -> events.stream()
                .map(MeterEvent::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            case COUNT -> BigDecimal.valueOf(events.size());

            case MAX -> events.stream()
                .map(MeterEvent::value)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);

            case LAST -> events.stream()
                .max(Comparator.comparing(MeterEvent::timestamp).thenComparing(MeterEvent::eventId))
                .map(MeterEvent::value)
                .orElse(BigDecimal.ZERO);

            case DISTINCT_COUNT -> {
                String propKey = definition.distinctProperty().orElse("userId");
                long distinct = events.stream()
                    .map(e -> e.properties().get(propKey))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .distinct()
                    .count();
                yield BigDecimal.valueOf(distinct);
            }
        };

        Instant lastEventTime = events.stream()
            .map(MeterEvent::timestamp)
            .max(Comparator.naturalOrder())
            .orElse(null);

        MeterAggregation aggregation = new MeterAggregation(
            tenantId,
            customerId,
            meterCode,
            window,
            aggregationType,
            aggregatedValue,
            events.size(),
            Optional.ofNullable(lastEventTime)
        );

        aggregationRepository.saveAggregation(aggregation);
        return aggregation;
    }

    @Override
    public List<MeterAggregation> aggregateAll(TenantId tenantId, Optional<CustomerId> customerId, TimeWindow window) {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(window, "window cannot be null");

        List<MeterEvent> allEvents = eventRepository.findAllEvents(tenantId, customerId, window.startTime(), window.endTime());
        Set<String> distinctMeterCodes = allEvents.stream()
            .map(e -> e.meterCode().toUpperCase())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        List<MeterAggregation> results = new ArrayList<>();
        for (String meterCode : distinctMeterCodes) {
            results.add(aggregate(tenantId, customerId, meterCode, window));
        }
        return results;
    }

    @Override
    public List<BillableItemRequest> generateBillableItems(TenantId tenantId, Optional<CustomerId> customerId, TimeWindow window) {
        List<MeterAggregation> aggregations = aggregateAll(tenantId, customerId, window);
        return aggregations.stream()
            .map(MeterAggregation::toBillableItemRequest)
            .toList();
    }
}
