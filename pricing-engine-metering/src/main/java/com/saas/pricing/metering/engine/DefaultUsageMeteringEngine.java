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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Enterprise implementation of the UsageMeteringEngine.
 * Implements strict event deduplication, out-of-order event handling with watermarks,
 * window-based multi-model aggregation, and billable item rating generation.
 *
 * <h2>Concurrency contract</h2>
 * <p>Ingestion and aggregation for the same {@code (tenant, meter)} take a shared stripe lock
 * (see {@link MeterLockRegistry}). This makes the cache-aside read-modify-aggregate-write in
 * {@link #aggregate} atomic against a concurrent {@link #ingest}, so a late event can never be
 * masked by a stale cache re-save.</p>
 */
public class DefaultUsageMeteringEngine implements UsageMeteringEngine {

    /**
     * Default maximum number of distinct property values retained for a DISTINCT_COUNT window.
     * Beyond this the aggregation is capped and flagged {@link MeterAggregation#isApproximate()}.
     */
    public static final int DEFAULT_MAX_DISTINCT_CARDINALITY = 100_000;

    private final IdempotencyStore idempotencyStore;
    private final MeterEventRepository eventRepository;
    private final MeterAggregationRepository aggregationRepository;
    private final MeterDefinitionRepository definitionRepository;
    private final Optional<Duration> allowedLateness;
    private final Clock clock;
    private final int maxDistinctCountCardinality;
    private final MeterLockRegistry lockRegistry;

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
        this(
            idempotencyStore,
            eventRepository,
            aggregationRepository,
            definitionRepository,
            allowedLateness,
            Clock.systemUTC(),
            DEFAULT_MAX_DISTINCT_CARDINALITY
        );
    }

    public DefaultUsageMeteringEngine(
        IdempotencyStore idempotencyStore,
        MeterEventRepository eventRepository,
        MeterAggregationRepository aggregationRepository,
        MeterDefinitionRepository definitionRepository,
        Optional<Duration> allowedLateness,
        Clock clock
    ) {
        this(
            idempotencyStore,
            eventRepository,
            aggregationRepository,
            definitionRepository,
            allowedLateness,
            clock,
            DEFAULT_MAX_DISTINCT_CARDINALITY
        );
    }

    public DefaultUsageMeteringEngine(
        IdempotencyStore idempotencyStore,
        MeterEventRepository eventRepository,
        MeterAggregationRepository aggregationRepository,
        MeterDefinitionRepository definitionRepository,
        Optional<Duration> allowedLateness,
        Clock clock,
        int maxDistinctCountCardinality
    ) {
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore cannot be null");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository cannot be null");
        this.aggregationRepository = Objects.requireNonNull(aggregationRepository, "aggregationRepository cannot be null");
        this.definitionRepository = Objects.requireNonNull(definitionRepository, "definitionRepository cannot be null");
        this.allowedLateness = Objects.requireNonNull(allowedLateness, "allowedLateness cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        if (maxDistinctCountCardinality <= 0) {
            throw new IllegalArgumentException("maxDistinctCountCardinality must be positive");
        }
        this.maxDistinctCountCardinality = maxDistinctCountCardinality;
        this.lockRegistry = new MeterLockRegistry();
    }

    @Override
    public IngestionResult ingest(MeterEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        // 1. Out-of-order Lateness / Watermark Check (must check before recording idempotency)
        // Evaluated against the injected clock so watermark decisions are deterministic under test.
        if (allowedLateness.isPresent()) {
            Instant oldestAllowed = clock.instant().minus(allowedLateness.get());
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

        // 2. Claim the idempotency key, persist, and invalidate the cache atomically w.r.t. aggregation.
        //    Holding the same (tenant, meter) stripe as aggregate() prevents an ingest from being
        //    masked by a stale aggregation cache re-save.
        ReentrantLock lock = lockRegistry.lockFor(event.tenantId(), event.meterCode());
        lock.lock();
        try {
            // 2. Idempotency Check
            Instant recordedAt = event.timestamp();
            boolean isNew = idempotencyStore.checkAndRecord(event.tenantId(), event.idempotencyKey(), recordedAt);
            if (!isNew) {
                return IngestionResult.duplicate(event.eventId(), event.idempotencyKey());
            }

            // 3. Persist Event. If the repository throws, the key we just consumed must be released,
            //    otherwise a legitimate retry is rejected as a duplicate forever.
            boolean saved;
            try {
                saved = eventRepository.saveEvent(event);
            } catch (RuntimeException e) {
                releaseIdempotencyKey(event, recordedAt);
                throw e;
            }
            if (!saved) {
                // The repository rejected it as already present: the key stays consumed, which is the
                // pre-existing behaviour for this branch. Rolling back here would let a replayed
                // event re-enter the pipeline and be re-tested against the repository on every retry.
                return IngestionResult.duplicate(event.eventId(), event.idempotencyKey());
            }

            // 4. Invalidate any existing cached aggregation covering this event timestamp
            aggregationRepository.invalidateForEvent(event.tenantId(), event.customerId(), event.meterCode(), event.timestamp());

            return IngestionResult.accepted(event.eventId(), event.idempotencyKey());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases an idempotency key claimed by a {@link #ingest} call that subsequently failed.
     * Compare-and-remove on the exact recorded value, so a key re-recorded concurrently by another
     * caller is never stolen back.
     */
    private void releaseIdempotencyKey(MeterEvent event, Instant recordedAt) {
        try {
            idempotencyStore.remove(event.tenantId(), event.idempotencyKey(), recordedAt);
        } catch (RuntimeException ignored) {
            // Rollback is best-effort; the original failure must propagate unchanged.
        }
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

        // The whole cache-aside read-modify-write runs under the meter stripe lock shared with
        // ingest(). Without it, an event arriving between the cache read and the cache write is
        // masked by the stale re-save and the window is under-billed from then on.
        ReentrantLock lock = lockRegistry.lockFor(tenantId, meterCode);
        lock.lock();
        try {
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
            boolean[] approximate = {false};
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

                case DISTINCT_COUNT -> distinctCount(events, definition, approximate);
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
                Optional.ofNullable(lastEventTime),
                approximate[0]
            );

            aggregationRepository.saveAggregation(aggregation);
            return aggregation;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Counts distinct property values, bounded by {@link #maxDistinctCountCardinality}.
     *
     * <p>The retained-value set is capped so a single pathological window cannot exhaust the heap.
     * On overflow the count is a lower bound and the result is flagged approximate, so downstream
     * rating can refuse to bill an undercount instead of silently charging less than owed.</p>
     *
     * <p>Memory: the cap bounds this to {@code maxDistinctCountCardinality} retained strings per
     * in-flight DISTINCT_COUNT aggregation (~100k short strings is a few MB). It is a per-window
     * ceiling on distinct values, not on events — a window with millions of repeated values costs the same.</p>
     */
    private BigDecimal distinctCount(List<MeterEvent> events, MeterDefinition definition, boolean[] approximateOut) {
        String propKey = definition.distinctProperty().orElse("userId");
        // Do not pre-size to the cap: most windows are small and the cap is large.
        Set<String> distinct = new HashSet<>(Math.min(maxDistinctCountCardinality, 1024));

        for (MeterEvent event : events) {
            Object raw = event.properties().get(propKey);
            if (raw == null) {
                continue;
            }
            String value = raw.toString();
            if (distinct.contains(value)) {
                continue;
            }
            if (distinct.size() >= maxDistinctCountCardinality) {
                // Refuse to retain more: the true count is >= cap and unknown from here.
                approximateOut[0] = true;
                break;
            }
            distinct.add(value);
        }
        return BigDecimal.valueOf(distinct.size());
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