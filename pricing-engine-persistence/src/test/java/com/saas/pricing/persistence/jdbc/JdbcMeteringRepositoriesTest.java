package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.AggregationType;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcMeteringRepositoriesTest extends BaseJdbcRepositoryTest {

    private JdbcMeterEventRepository eventRepository;
    private JdbcMeterAggregationRepository aggregationRepository;

    private final TenantId tenantId = TenantId.of("tenant_meter_jdbc");
    private final CustomerId customerId = CustomerId.of("cust_meter_jdbc");
    private final Instant baseTime = Instant.parse("2026-10-08T18:00:00Z");

    @BeforeEach
    void setUp() {
        eventRepository = new JdbcMeterEventRepository(jdbcTemplate);
        aggregationRepository = new JdbcMeterAggregationRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("Should persist meter events and prevent duplicate idempotency keys in database")
    void testMeterEventPersistenceAndIdempotency() {
        MeterEvent event1 = MeterEvent.builder()
            .eventId("evt_jdbc_1")
            .idempotencyKey("key_abc")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("API_CALLS")
            .value(new BigDecimal("10"))
            .timestamp(baseTime.plusSeconds(30))
            .property("ip", "192.168.1.1")
            .build();

        MeterEvent duplicate = MeterEvent.builder()
            .eventId("evt_jdbc_2")
            .idempotencyKey("key_abc") // duplicate key
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("API_CALLS")
            .value(new BigDecimal("10"))
            .timestamp(baseTime.plusSeconds(30))
            .build();

        boolean saved1 = eventRepository.saveEvent(event1);
        boolean savedDuplicate = eventRepository.saveEvent(duplicate);

        assertThat(saved1).isTrue();
        assertThat(savedDuplicate).isFalse();
        assertThat(eventRepository.isDuplicate(tenantId, "key_abc")).isTrue();

        List<MeterEvent> events = eventRepository.findEvents(
            tenantId, Optional.of(customerId), "API_CALLS", baseTime, baseTime.plus(Duration.ofHours(1))
        );
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventId()).isEqualTo("evt_jdbc_1");
        assertThat(events.getFirst().properties()).containsEntry("ip", "192.168.1.1");
    }

    @Test
    @DisplayName("Should save, find, and invalidate materialized aggregations in database")
    void testMeterAggregationPersistence() {
        TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

        MeterAggregation agg = new MeterAggregation(
            tenantId,
            Optional.of(customerId),
            "API_CALLS",
            window,
            AggregationType.SUM,
            new BigDecimal("150.00"),
            15,
            Optional.of(baseTime.plusSeconds(1800))
        );

        aggregationRepository.saveAggregation(agg);

        Optional<MeterAggregation> found = aggregationRepository.findAggregation(
            tenantId, Optional.of(customerId), "API_CALLS", window
        );
        assertThat(found).isPresent();
        assertThat(found.get().aggregatedValue()).isEqualByComparingTo("150.00");
        assertThat(found.get().eventCount()).isEqualTo(15);

        // Invalidate
        aggregationRepository.invalidate(tenantId, Optional.of(customerId), "API_CALLS", window);
        assertThat(aggregationRepository.findAggregation(tenantId, Optional.of(customerId), "API_CALLS", window)).isEmpty();
    }

    @Test
    @DisplayName("Should invalidate cached aggregation overlapping event timestamp")
    void testInvalidateForEvent() {
        TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

        MeterAggregation agg = new MeterAggregation(
            tenantId,
            Optional.of(customerId),
            "STORAGE_BYTES",
            window,
            AggregationType.SUM,
            new BigDecimal("500"),
            5,
            Optional.of(baseTime.plusSeconds(300))
        );

        aggregationRepository.saveAggregation(agg);
        assertThat(aggregationRepository.findAggregation(tenantId, Optional.of(customerId), "STORAGE_BYTES", window)).isPresent();

        // Invalidate by event timestamp inside the window
        aggregationRepository.invalidateForEvent(tenantId, Optional.of(customerId), "STORAGE_BYTES", baseTime.plusSeconds(600));

        assertThat(aggregationRepository.findAggregation(tenantId, Optional.of(customerId), "STORAGE_BYTES", window)).isEmpty();
    }

    @Test
    @DisplayName("Should find all aggregations across all customers when customerId is empty")
    void testFindAllAggregationsWhenCustomerIdEmpty() {
        TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

        CustomerId cust1 = CustomerId.of("cust_alpha");
        CustomerId cust2 = CustomerId.of("cust_beta");

        MeterAggregation agg1 = new MeterAggregation(
            tenantId, Optional.of(cust1), "API_CALLS", window,
            AggregationType.COUNT, BigDecimal.valueOf(10), 10, Optional.empty()
        );
        MeterAggregation agg2 = new MeterAggregation(
            tenantId, Optional.of(cust2), "STORAGE", window,
            AggregationType.SUM, BigDecimal.valueOf(200), 2, Optional.empty()
        );

        aggregationRepository.saveAggregation(agg1);
        aggregationRepository.saveAggregation(agg2);

        List<MeterAggregation> allAggs = aggregationRepository.findAllAggregations(tenantId, Optional.empty(), window);
        assertThat(allAggs).hasSize(2);
        assertThat(allAggs).extracting(MeterAggregation::meterCode).containsExactlyInAnyOrder("API_CALLS", "STORAGE");
    }
}
