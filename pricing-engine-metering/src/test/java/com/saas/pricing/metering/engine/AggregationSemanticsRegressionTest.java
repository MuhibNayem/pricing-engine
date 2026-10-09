package com.saas.pricing.metering.engine;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEFECT 8 - regression locks for the aggregation semantics that two independent audits confirmed as
 * correct. These behaviours must not regress while fixing the concurrency defects around them.
 */
class AggregationSemanticsRegressionTest {

    private final TenantId tenantId = TenantId.of("tenant_sem");
    private final CustomerId customerId = CustomerId.of("cust_sem");
    private final Instant baseTime = Instant.parse("2026-10-08T10:00:00Z");
    private final TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

    private DefaultUsageMeteringEngine engine;

    @BeforeEach
    void setUp() {
        InMemoryMeterDefinitionRepository definitions = new InMemoryMeterDefinitionRepository();
        definitions.saveDefinition(MeterDefinition.sum("SUM_METER", "sum"));
        definitions.saveDefinition(MeterDefinition.count("COUNT_METER", "count"));
        definitions.saveDefinition(MeterDefinition.max("MAX_METER", "max"));
        definitions.saveDefinition(MeterDefinition.last("LAST_METER", "last"));

        engine = new DefaultUsageMeteringEngine(
            new InMemoryIdempotencyStore(),
            new InMemoryMeterEventRepository(),
            new InMemoryMeterAggregationRepository(),
            definitions,
            Optional.empty()
        );
    }

    private MeterEvent event(String eventId, String meterCode, String value, Instant ts) {
        return MeterEvent.builder()
            .eventId(eventId)
            .idempotencyKey("idem_" + eventId)
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode(meterCode)
            .value(new BigDecimal(value))
            .timestamp(ts)
            .build();
    }

    // ---------------------------------------------------------------------
    // Window assignment via TimeWindow containment ([start, end))
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Window assignment is half-open: start inclusive, end exclusive")
    void windowAssignmentIsHalfOpen() {
        engine.ingest(event("evt_at_start", "SUM_METER", "10", baseTime));                    // included
        engine.ingest(event("evt_at_end", "SUM_METER", "1000", baseTime.plusSeconds(3600)));   // excluded
        engine.ingest(event("evt_before", "SUM_METER", "1000", baseTime.minusSeconds(1)));     // excluded
        engine.ingest(event("evt_inside", "SUM_METER", "5", baseTime.plusSeconds(1800)));     // included

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", window);

        assertThat(aggregation.eventCount()).isEqualTo(2);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("TimeWindow.contains agrees with the engine's window assignment")
    void timeWindowContainmentMatchesEngineAssignment() {
        assertThat(window.contains(baseTime)).isTrue();
        assertThat(window.contains(baseTime.plusSeconds(3600))).isFalse();

        engine.ingest(event("evt_edge", "SUM_METER", "7", baseTime));
        assertThat(window.contains(baseTime)).isTrue();

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", window);
        assertThat(aggregation.eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Events outside the window are cached per window and never leak across windows")
    void windowsAreIsolated() {
        TimeWindow other = TimeWindow.of(baseTime.plusSeconds(3600), baseTime.plusSeconds(7200));

        engine.ingest(event("evt_w1", "SUM_METER", "3", baseTime.plusSeconds(10)));
        engine.ingest(event("evt_w2", "SUM_METER", "4", other.startTime().plusSeconds(10)));

        assertThat(engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", window)
            .aggregatedValue()).isEqualByComparingTo("3");
        assertThat(engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", other)
            .aggregatedValue()).isEqualByComparingTo("4");
    }

    // ---------------------------------------------------------------------
    // SUM / COUNT / MAX / LAST semantics
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("SUM adds all event values in the window")
    void sumSemantics() {
        engine.ingest(event("e1", "SUM_METER", "10", baseTime.plusSeconds(1)));
        engine.ingest(event("e2", "SUM_METER", "20.5", baseTime.plusSeconds(2)));
        engine.ingest(event("e3", "SUM_METER", "0.5", baseTime.plusSeconds(3)));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", window);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("31.0");
        assertThat(aggregation.eventCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("COUNT counts events regardless of their values")
    void countSemantics() {
        engine.ingest(event("c1", "COUNT_METER", "999", baseTime.plusSeconds(1)));
        engine.ingest(event("c2", "COUNT_METER", "0", baseTime.plusSeconds(2)));
        engine.ingest(event("c3", "COUNT_METER", "-5", baseTime.plusSeconds(3)));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "COUNT_METER", window);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("3");
        assertThat(aggregation.eventCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("MAX returns the highest stored event value")
    void maxSemantics() {
        engine.ingest(event("m1", "MAX_METER", "12", baseTime.plusSeconds(1)));
        engine.ingest(event("m2", "MAX_METER", "98", baseTime.plusSeconds(2)));
        engine.ingest(event("m3", "MAX_METER", "45", baseTime.plusSeconds(3)));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "MAX_METER", window);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("98");
    }

    @Test
    @DisplayName("MAX handles negative values correctly")
    void maxHandlesNegatives() {
        engine.ingest(event("mn1", "MAX_METER", "-10", baseTime.plusSeconds(1)));
        engine.ingest(event("mn2", "MAX_METER", "-3", baseTime.plusSeconds(2)));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "MAX_METER", window);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("-3");
    }

    @Test
    @DisplayName("MAX returns the stored maximum, not a high-water mark ratcheted up by duplicate replay")
    void maxIsStoredMaxNotHighWaterMarkOnReplay() {
        engine.ingest(event("mhw", "MAX_METER", "5", baseTime.plusSeconds(1)));

        // Replay the same identity carrying a higher value: it must be rejected as a duplicate
        // and must NOT ratchet the stored maximum up to 99.
        MeterEvent replay = MeterEvent.builder()
            .eventId("mhw")
            .idempotencyKey("idem_mhw")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("MAX_METER")
            .value(new BigDecimal("99"))
            .timestamp(baseTime.plusSeconds(1))
            .build();
        assertThat(engine.ingest(replay).isDuplicate()).isTrue();

        // Repeat the replay once more with a different timestamp to be sure.
        assertThat(engine.ingest(MeterEvent.builder()
            .eventId("mhw")
            .idempotencyKey("idem_mhw")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("MAX_METER")
            .value(new BigDecimal("99"))
            .timestamp(baseTime.plusSeconds(2))
            .build()).isDuplicate()).isTrue();

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "MAX_METER", window);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("5");
        assertThat(aggregation.eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("LAST returns the value of the latest event by timestamp")
    void lastSemantics() {
        engine.ingest(event("l_late", "LAST_METER", "40", baseTime.plusSeconds(300)));
        engine.ingest(event("l_early", "LAST_METER", "15", baseTime.plusSeconds(60)));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "LAST_METER", window);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("40");
        assertThat(aggregation.lastEventTime()).contains(baseTime.plusSeconds(300));
    }

    @Test
    @DisplayName("LAST breaks timestamp ties on the highest eventId, deterministically")
    void lastTieBreaksOnEventId() {
        Instant sameTs = baseTime.plusSeconds(500);

        engine.ingest(event("evt_A", "LAST_METER", "10", sameTs));
        engine.ingest(event("evt_B", "LAST_METER", "20", sameTs));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "LAST_METER", window);

        // "evt_B" > "evt_A" on the tie-breaker, so 20 wins.
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("LAST tie-break is independent of the order events were ingested")
    void lastTieBreakIsOrderIndependent() {
        Instant sameTs = baseTime.plusSeconds(500);

        InMemoryMeterDefinitionRepository definitions = definitions();
        InMemoryMeterEventRepository repo = new InMemoryMeterEventRepository();

        MeterEvent a = event("evt_A", "LAST_METER", "10", sameTs);
        MeterEvent b = event("evt_B", "LAST_METER", "20", sameTs);

        // Insert in both orders into two repositories.
        repo.saveEvent(a);
        repo.saveEvent(b);
        var forward = new DefaultUsageMeteringEngine(
            new InMemoryIdempotencyStore(), repo,
            new InMemoryMeterAggregationRepository(), definitions, Optional.empty());

        InMemoryMeterEventRepository repo2 = new InMemoryMeterEventRepository();
        repo2.saveEvent(b);
        repo2.saveEvent(a);
        var reverse = new DefaultUsageMeteringEngine(
            new InMemoryIdempotencyStore(), repo2,
            new InMemoryMeterAggregationRepository(), definitions, Optional.empty());

        MeterAggregation forwardAgg = forward.aggregate(tenantId, Optional.of(customerId), "LAST_METER", window);
        MeterAggregation reverseAgg = reverse.aggregate(tenantId, Optional.of(customerId), "LAST_METER", window);

        assertThat(forwardAgg.aggregatedValue()).isEqualByComparingTo("20");
        assertThat(reverseAgg.aggregatedValue()).isEqualByComparingTo("20");
        assertThat(forwardAgg.aggregatedValue()).isEqualByComparingTo(reverseAgg.aggregatedValue());
    }

    @Test
    @DisplayName("lastEventTime reports the max timestamp regardless of ingestion order")
    void lastEventTimeIsMaxTimestamp() {
        engine.ingest(event("t_late", "LAST_METER", "1", baseTime.plusSeconds(900)));
        engine.ingest(event("t_early", "LAST_METER", "1", baseTime.plusSeconds(10)));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "LAST_METER", window);
        assertThat(aggregation.lastEventTime()).contains(baseTime.plusSeconds(900));
    }

    @Test
    @DisplayName("An unknown meter defaults to SUM aggregation")
    void unknownMeterDefaultsToSum() {
        engine.ingest(event("u1", "UNDECLARED_METER", "6", baseTime.plusSeconds(1)));
        engine.ingest(event("u2", "UNDECLARED_METER", "7", baseTime.plusSeconds(2)));

        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "UNDECLARED_METER", window);
        assertThat(aggregation.aggregationType().name()).isEqualTo("SUM");
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("13");
    }

    @Test
    @DisplayName("Empty window aggregates to zero with no events")
    void emptyWindowAggregatesToZero() {
        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", window);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aggregation.eventCount()).isZero();
        assertThat(aggregation.lastEventTime()).isEmpty();
        assertThat(aggregation.isApproximate()).isFalse();
    }

    @Test
    @DisplayName("Events are isolated per customer")
    void eventsAreIsolatedPerCustomer() {
        CustomerId other = CustomerId.of("cust_other");
        engine.ingest(event("cust_a", "SUM_METER", "10", baseTime.plusSeconds(1)));
        engine.ingest(MeterEvent.builder()
            .eventId("cust_b")
            .idempotencyKey("idem_cust_b")
            .tenantId(tenantId)
            .customerId(other)
            .meterCode("SUM_METER")
            .value(new BigDecimal("99"))
            .timestamp(baseTime.plusSeconds(2))
            .build());

        assertThat(engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", window)
            .aggregatedValue()).isEqualByComparingTo("10");
        assertThat(engine.aggregate(tenantId, Optional.of(other), "SUM_METER", window)
            .aggregatedValue()).isEqualByComparingTo("99");
    }

    @Test
    @DisplayName("Events are isolated per tenant")
    void eventsAreIsolatedPerTenant() {
        TenantId otherTenant = TenantId.of("tenant_other");
        engine.ingest(event("t_a", "SUM_METER", "10", baseTime.plusSeconds(1)));
        engine.ingest(MeterEvent.builder()
            .eventId("t_b")
            .idempotencyKey("idem_t_b")
            .tenantId(otherTenant)
            .customerId(customerId)
            .meterCode("SUM_METER")
            .value(new BigDecimal("77"))
            .timestamp(baseTime.plusSeconds(2))
            .build());

        assertThat(engine.aggregate(tenantId, Optional.of(customerId), "SUM_METER", window)
            .aggregatedValue()).isEqualByComparingTo("10");
        assertThat(engine.aggregate(otherTenant, Optional.of(customerId), "SUM_METER", window)
            .aggregatedValue()).isEqualByComparingTo("77");
    }

    private InMemoryMeterDefinitionRepository definitions() {
        InMemoryMeterDefinitionRepository definitions = new InMemoryMeterDefinitionRepository();
        definitions.saveDefinition(MeterDefinition.sum("SUM_METER", "sum"));
        definitions.saveDefinition(MeterDefinition.count("COUNT_METER", "count"));
        definitions.saveDefinition(MeterDefinition.max("MAX_METER", "max"));
        definitions.saveDefinition(MeterDefinition.last("LAST_METER", "last"));
        return definitions;
    }
}