package com.saas.pricing.metering.engine;

import com.saas.pricing.core.model.BillableItemRequest;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEFECT 6 - DISTINCT_COUNT must have a bounded, documented cardinality. Once the cap is reached the
 * engine stops retaining values and flags the result approximate, so downstream rating can refuse
 * to bill a capped undercount instead of silently charging less than owed.
 */
class DistinctCountCardinalityTest {

    private final TenantId tenantId = TenantId.of("tenant_dc");
    private final CustomerId customerId = CustomerId.of("cust_dc");
    private final Instant baseTime = Instant.parse("2026-10-08T10:00:00Z");
    private final TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

    private DefaultUsageMeteringEngine engineWith(int maxCardinality) {
        InMemoryMeterDefinitionRepository definitions = new InMemoryMeterDefinitionRepository();
        definitions.saveDefinition(MeterDefinition.distinctCount("ACTIVE_USERS", "userId", "Unique active users"));
        return new DefaultUsageMeteringEngine(
            new InMemoryIdempotencyStore(),
            new InMemoryMeterEventRepository(),
            new InMemoryMeterAggregationRepository(),
            definitions,
            Optional.empty(),
            Clock.systemUTC(),
            maxCardinality
        );
    }

    private void ingestDistinctUsers(DefaultUsageMeteringEngine engine, int distinctUsers, int repeats) {
        int seq = 0;
        for (int user = 0; user < distinctUsers; user++) {
            for (int r = 0; r < repeats; r++) {
                engine.ingest(MeterEvent.builder()
                    .eventId("evt_" + seq++)
                    .idempotencyKey("idem_" + seq)
                    .tenantId(tenantId)
                    .customerId(customerId)
                    .meterCode("ACTIVE_USERS")
                    .property("userId", "user_" + user)
                    .timestamp(baseTime.plusSeconds(seq % 3000))
                    .build());
            }
        }
    }

    @Test
    @DisplayName("Cardinality within the cap is exact and not flagged approximate")
    void withinCardinalityIsExact() {
        DefaultUsageMeteringEngine engine = engineWith(100);
        ingestDistinctUsers(engine, 40, 3);

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "ACTIVE_USERS", window);

        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("40");
        assertThat(aggregation.approximate()).isFalse();
        assertThat(aggregation.isApproximate()).isFalse();
        assertThat(aggregation.eventCount()).isEqualTo(120);
    }

    @Test
    @DisplayName("Exceeding the cap caps the count and flags the aggregation approximate")
    void exceedingCardinalityIsCappedAndFlagged() {
        int cap = 50;
        DefaultUsageMeteringEngine engine = engineWith(cap);
        ingestDistinctUsers(engine, 500, 1);

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "ACTIVE_USERS", window);

        // Capped at the configured ceiling...
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo(String.valueOf(cap));
        // ...and explicitly flagged, because the real count is >= cap and unknown.
        assertThat(aggregation.approximate()).isTrue();
        assertThat(aggregation.isApproximate()).isTrue();
        // All events are still counted, so the shortfall is visible.
        assertThat(aggregation.eventCount()).isEqualTo(500);
    }

    @Test
    @DisplayName("Approximate flag is propagated to the billable item so rating can refuse to bill")
    void approximateFlagReachesBillableItem() {
        DefaultUsageMeteringEngine engine = engineWith(10);
        ingestDistinctUsers(engine, 25, 1);

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "ACTIVE_USERS", window);
        assertThat(aggregation.isApproximate()).isTrue();

        BillableItemRequest item = aggregation.toBillableItemRequest();
        assertThat(item.attributes()).containsEntry("approximate", true);
        assertThat(item.attributes()).containsEntry("aggregationType", "DISTINCT_COUNT");
    }

    @Test
    @DisplayName("Default cardinality cap is documented and generous")
    void defaultCardinalityIsDocumented() {
        assertThat(DefaultUsageMeteringEngine.DEFAULT_MAX_DISTINCT_CARDINALITY).isEqualTo(100_000);
    }

    @Test
    @DisplayName("Non-positive cardinality cap is rejected at construction")
    void nonPositiveCardinalityRejected() {
        assertThatThrownBy(() -> engineWith(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxDistinctCountCardinality must be positive");
    }

    @Test
    @DisplayName("Events without the distinct property do not inflate the count")
    void missingPropertyIsIgnored() {
        DefaultUsageMeteringEngine engine = engineWith(100);
        for (int i = 0; i < 5; i++) {
            engine.ingest(MeterEvent.builder()
                .eventId("evt_noprop_" + i)
                .idempotencyKey("idem_noprop_" + i)
                .tenantId(tenantId)
                .customerId(customerId)
                .meterCode("ACTIVE_USERS")
                .timestamp(baseTime.plusSeconds(i))
                .build());
        }

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "ACTIVE_USERS", window);

        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aggregation.isApproximate()).isFalse();
    }
}