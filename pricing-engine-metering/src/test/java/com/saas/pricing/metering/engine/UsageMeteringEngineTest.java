package com.saas.pricing.metering.engine;

import com.saas.pricing.core.engine.DefaultPricingEngine;
import com.saas.pricing.core.model.BillableItemRequest;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.PricingResult;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.CurrencyExchangeProvider;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.TaxProvider;
import com.saas.pricing.core.spi.impl.InMemoryCurrencyExchangeProvider;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import com.saas.pricing.core.spi.impl.RuleBasedTaxProvider;
import com.saas.pricing.metering.model.AggregationType;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.MeterDefinition;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.metering.spi.impl.InMemoryIdempotencyStore;
import com.saas.pricing.metering.spi.impl.InMemoryMeterAggregationRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterDefinitionRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UsageMeteringEngineTest {

    private InMemoryIdempotencyStore idempotencyStore;
    private InMemoryMeterEventRepository eventRepository;
    private InMemoryMeterAggregationRepository aggregationRepository;
    private InMemoryMeterDefinitionRepository definitionRepository;
    private DefaultUsageMeteringEngine meteringEngine;

    private final TenantId tenantId = TenantId.of("tenant_acme");
    private final CustomerId customerId = CustomerId.of("cust_001");
    private final Instant baseTime = Instant.parse("2026-10-08T10:00:00Z");
    private final TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

    @BeforeEach
    void setUp() {
        idempotencyStore = new InMemoryIdempotencyStore();
        eventRepository = new InMemoryMeterEventRepository();
        aggregationRepository = new InMemoryMeterAggregationRepository();
        definitionRepository = new InMemoryMeterDefinitionRepository();

        // Register meter definitions
        definitionRepository.saveDefinition(MeterDefinition.sum("STORAGE_BYTES", "Storage consumed"));
        definitionRepository.saveDefinition(MeterDefinition.count("API_CALLS", "API calls count"));
        definitionRepository.saveDefinition(MeterDefinition.max("CONCURRENT_STREAMS", "Peak concurrent streams"));
        definitionRepository.saveDefinition(MeterDefinition.last("ACTIVE_SEATS", "Seat gauge"));
        definitionRepository.saveDefinition(MeterDefinition.distinctCount("ACTIVE_USERS", "userId", "Unique active users"));

        meteringEngine = new DefaultUsageMeteringEngine(
            idempotencyStore,
            eventRepository,
            aggregationRepository,
            definitionRepository,
            Optional.of(Duration.ofDays(7)) // 7-day allowed lateness
        );
    }

    @Test
    @DisplayName("Should aggregate SUM correctly across events in window")
    void testSumAggregation() {
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("1000"))
            .timestamp(baseTime.plusSeconds(60))
            .build());

        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("2500"))
            .timestamp(baseTime.plusSeconds(120))
            .build());

        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("500"))
            .timestamp(baseTime.plusSeconds(180))
            .build());

        MeterAggregation aggregation = meteringEngine.aggregate(tenantId, Optional.of(customerId), "STORAGE_BYTES", window);

        assertThat(aggregation.aggregationType()).isEqualTo(AggregationType.SUM);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("4000");
        assertThat(aggregation.eventCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should aggregate COUNT correctly")
    void testCountAggregation() {
        for (int i = 0; i < 5; i++) {
            meteringEngine.ingest(MeterEvent.builder()
                .tenantId(tenantId)
                .customerId(customerId)
                .meterCode("API_CALLS")
                .value(BigDecimal.ONE)
                .timestamp(baseTime.plusSeconds(i * 10))
                .build());
        }

        MeterAggregation aggregation = meteringEngine.aggregate(tenantId, Optional.of(customerId), "API_CALLS", window);

        assertThat(aggregation.aggregationType()).isEqualTo(AggregationType.COUNT);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("5");
        assertThat(aggregation.eventCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should aggregate MAX / high-water mark correctly")
    void testMaxAggregation() {
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("CONCURRENT_STREAMS")
            .value(new BigDecimal("12"))
            .timestamp(baseTime.plusSeconds(30))
            .build());

        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("CONCURRENT_STREAMS")
            .value(new BigDecimal("98")) // peak
            .timestamp(baseTime.plusSeconds(60))
            .build());

        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("CONCURRENT_STREAMS")
            .value(new BigDecimal("45"))
            .timestamp(baseTime.plusSeconds(90))
            .build());

        MeterAggregation aggregation = meteringEngine.aggregate(tenantId, Optional.of(customerId), "CONCURRENT_STREAMS", window);

        assertThat(aggregation.aggregationType()).isEqualTo(AggregationType.MAX);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("98");
    }

    @Test
    @DisplayName("Should aggregate LAST / gauge correctly even with out-of-order event ingestion")
    void testLastGaugeWithOutOfOrderArrival() {
        // Event 1: timestamp at +50 min, value = 25
        MeterEvent e1 = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("ACTIVE_SEATS")
            .value(new BigDecimal("25"))
            .timestamp(baseTime.plusSeconds(3000))
            .build();

        // Event 2 (chronologically earlier, but ingested second): timestamp at +10 min, value = 15
        MeterEvent e2 = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("ACTIVE_SEATS")
            .value(new BigDecimal("15"))
            .timestamp(baseTime.plusSeconds(600))
            .build();

        // Event 3 (chronologically latest): timestamp at +55 min, value = 40
        MeterEvent e3 = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("ACTIVE_SEATS")
            .value(new BigDecimal("40"))
            .timestamp(baseTime.plusSeconds(3300))
            .build();

        // Ingest in out-of-order sequence: e2, then e3, then e1
        meteringEngine.ingest(e2);
        meteringEngine.ingest(e3);
        meteringEngine.ingest(e1);

        MeterAggregation aggregation = meteringEngine.aggregate(tenantId, Optional.of(customerId), "ACTIVE_SEATS", window);

        assertThat(aggregation.aggregationType()).isEqualTo(AggregationType.LAST);
        // Latest timestamp in window is e3 at +3300s, value = 40
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("40");
        assertThat(aggregation.lastEventTime()).contains(baseTime.plusSeconds(3300));
    }

    @Test
    @DisplayName("Should aggregate DISTINCT_COUNT of specified property")
    void testDistinctCountAggregation() {
        // 4 events from "user_A", 2 from "user_B", 1 from "user_C" -> 3 distinct users
        String[] users = {"user_A", "user_B", "user_A", "user_C", "user_B", "user_A", "user_A"};

        for (int i = 0; i < users.length; i++) {
            meteringEngine.ingest(MeterEvent.builder()
                .tenantId(tenantId)
                .customerId(customerId)
                .meterCode("ACTIVE_USERS")
                .property("userId", users[i])
                .timestamp(baseTime.plusSeconds(i * 60))
                .build());
        }

        MeterAggregation aggregation = meteringEngine.aggregate(tenantId, Optional.of(customerId), "ACTIVE_USERS", window);

        assertThat(aggregation.aggregationType()).isEqualTo(AggregationType.DISTINCT_COUNT);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("3");
        assertThat(aggregation.eventCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("Should detect and deduplicate events sharing the same idempotency key")
    void testEventDeduplication() {
        MeterEvent first = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .idempotencyKey("idem-key-100")
            .value(new BigDecimal("500"))
            .timestamp(baseTime.plusSeconds(10))
            .build();

        MeterEvent duplicate = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .idempotencyKey("idem-key-100") // SAME idempotency key
            .value(new BigDecimal("500"))
            .timestamp(baseTime.plusSeconds(10))
            .build();

        IngestionResult res1 = meteringEngine.ingest(first);
        IngestionResult res2 = meteringEngine.ingest(duplicate);

        assertThat(res1.isAccepted()).isTrue();
        assertThat(res2.isDuplicate()).isTrue();
        assertThat(res2.isAccepted()).isFalse();

        // Aggregation must only count the event ONCE
        MeterAggregation agg = meteringEngine.aggregate(tenantId, Optional.of(customerId), "STORAGE_BYTES", window);
        assertThat(agg.aggregatedValue()).isEqualByComparingTo("500");
        assertThat(agg.eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should reject events that arrive after the allowed lateness watermark")
    void testLateEventRejection() {
        // Event older than 7 days
        Instant veryOldTime = Instant.now().minus(Duration.ofDays(10));
        MeterEvent lateEvent = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("100"))
            .timestamp(veryOldTime)
            .build();

        IngestionResult result = meteringEngine.ingest(lateEvent);

        assertThat(result.isRejectedLate()).isTrue();
        assertThat(result.isAccepted()).isFalse();
        assertThat(result.message()).contains("older than maximum allowed lateness");
    }

    @Test
    @DisplayName("Should generate BillableItemRequests from aggregations and rate with PricingEngine")
    void testIntegrationWithPricingEngine() {
        // 1. Ingest events for multiple meters
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("API_CALLS")
            .value(BigDecimal.ONE)
            .timestamp(baseTime.plusSeconds(10))
            .build());

        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("API_CALLS")
            .value(BigDecimal.ONE)
            .timestamp(baseTime.plusSeconds(20))
            .build());

        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("100"))
            .timestamp(baseTime.plusSeconds(30))
            .build());

        // 2. Generate BillableItemRequests
        List<BillableItemRequest> billableItems = meteringEngine.generateBillableItems(tenantId, Optional.of(customerId), window);

        assertThat(billableItems).hasSize(2);
        assertThat(billableItems).anySatisfy(item -> {
            if (item.itemCode().equals("API_CALLS")) {
                assertThat(item.quantity()).isEqualByComparingTo("2");
                assertThat(item.attributes()).containsKey("windowStart");
            }
        });

        // 3. Setup PricingEngine with rate card matching these meters
        RateCardRepository rateCardRepo = new InMemoryRateCardRepository();
        RateCard rateCard = RateCard.of(
            "rc_test",
            tenantId,
            PlanCode.of("USAGE_PLAN"),
            1,
            Instant.EPOCH,
            List.of(
                RatePlanItem.of("API_CALLS", "Calls", PricingModel.PerUnitModel.of(new BigDecimal("0.05")), CurrencyUnit.USD),
                RatePlanItem.of("STORAGE_BYTES", "Storage", PricingModel.PerUnitModel.of(new BigDecimal("0.10")), CurrencyUnit.USD)
            )
        );
        rateCardRepo.save(rateCard);

        DefaultPricingEngine pricingEngine = new DefaultPricingEngine(
            rateCardRepo,
            new InMemoryCurrencyExchangeProvider(),
            new RuleBasedTaxProvider(),
            AuditSink.noOp(),
            null
        );

        // 4. Rate with PricingEngine
        PricingRequest.Builder requestBuilder = PricingRequest.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .planCode("USAGE_PLAN")
            .evaluationTime(window.endTime())
            .targetCurrency(CurrencyUnit.USD);

        billableItems.forEach(requestBuilder::item);
        PricingResult pricingResult = pricingEngine.evaluate(requestBuilder.build());

        // 2 API calls * 0.05 = $0.10; 100 storage * 0.10 = $10.00; Total = $10.10
        assertThat(pricingResult.finalTotal().amount()).isEqualByComparingTo("10.10");
        assertThat(pricingResult.lineItems()).hasSize(2);
    }

    @Test
    @DisplayName("Should invalidate cached aggregation when an out-of-order event arrives for that window")
    void testOutOfOrderEventInvalidatesCachedAggregation() {
        // Initial event
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("100"))
            .timestamp(baseTime.plusSeconds(300))
            .build());

        // Query aggregation -> caches result 100
        MeterAggregation firstAgg = meteringEngine.aggregate(tenantId, Optional.of(customerId), "STORAGE_BYTES", window);
        assertThat(firstAgg.aggregatedValue()).isEqualByComparingTo("100");
        assertThat(firstAgg.eventCount()).isEqualTo(1);

        // Out-of-order event arrives inside the same window
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("50"))
            .timestamp(baseTime.plusSeconds(100))
            .build());

        // Second aggregation MUST reflect both events, not return stale cached value
        MeterAggregation secondAgg = meteringEngine.aggregate(tenantId, Optional.of(customerId), "STORAGE_BYTES", window);
        assertThat(secondAgg.aggregatedValue()).isEqualByComparingTo("150");
        assertThat(secondAgg.eventCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Late event rejection must not record idempotency key or mark key as duplicate")
    void testLateEventDoesNotPolluteIdempotencyStore() {
        Instant veryOldTime = Instant.now().minus(Duration.ofDays(10));
        MeterEvent lateEvent = MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .idempotencyKey("late-key-1")
            .value(new BigDecimal("100"))
            .timestamp(veryOldTime)
            .build();

        IngestionResult firstResult = meteringEngine.ingest(lateEvent);
        assertThat(firstResult.isRejectedLate()).isTrue();
        assertThat(idempotencyStore.isDuplicate(tenantId, "late-key-1")).isFalse();

        // Resending late event must still be rejected as late, NOT reported as duplicate
        IngestionResult retryResult = meteringEngine.ingest(lateEvent);
        assertThat(retryResult.isRejectedLate()).isTrue();
        assertThat(retryResult.isDuplicate()).isFalse();
    }

    @Test
    @DisplayName("Empty window aggregation must be invalidated when a new event arrives")
    void testEmptyAggregationInvalidatedWhenEventIngested() {
        // Query empty window
        MeterAggregation empty = meteringEngine.aggregate(tenantId, Optional.of(customerId), "API_CALLS", window);
        assertThat(empty.aggregatedValue()).isEqualByComparingTo("0");
        assertThat(empty.eventCount()).isEqualTo(0);

        // Ingest event into the window
        meteringEngine.ingest(MeterEvent.builder()
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("API_CALLS")
            .value(BigDecimal.ONE)
            .timestamp(baseTime.plusSeconds(50))
            .build());

        // Subsequent query MUST return fresh count 1, not 0
        MeterAggregation updated = meteringEngine.aggregate(tenantId, Optional.of(customerId), "API_CALLS", window);
        assertThat(updated.aggregatedValue()).isEqualByComparingTo("1");
        assertThat(updated.eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("LAST gauge aggregation must be deterministic even with identical timestamps")
    void testLastGaugeTieBreakerDeterminism() {
        Instant sameTs = baseTime.plusSeconds(500);

        MeterEvent e1 = MeterEvent.builder()
            .eventId("evt_A")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("ACTIVE_SEATS")
            .value(new BigDecimal("10"))
            .timestamp(sameTs)
            .build();

        MeterEvent e2 = MeterEvent.builder()
            .eventId("evt_B")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("ACTIVE_SEATS")
            .value(new BigDecimal("20"))
            .timestamp(sameTs)
            .build();

        meteringEngine.ingest(e1);
        meteringEngine.ingest(e2);

        MeterAggregation agg = meteringEngine.aggregate(tenantId, Optional.of(customerId), "ACTIVE_SEATS", window);
        // By eventId tie-breaker, "evt_B" > "evt_A" -> value is 20
        assertThat(agg.aggregatedValue()).isEqualByComparingTo("20");
    }
}
