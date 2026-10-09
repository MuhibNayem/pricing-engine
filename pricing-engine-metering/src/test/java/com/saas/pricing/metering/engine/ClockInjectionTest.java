package com.saas.pricing.metering.engine;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.IngestionResult;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEFECT 7 - the lateness watermark is evaluated against an injectable {@link Clock}, so late-event
 * decisions are deterministic and testable instead of depending on wall-clock time.
 */
class ClockInjectionTest {

    private final TenantId tenantId = TenantId.of("tenant_clock");
    private final CustomerId customerId = CustomerId.of("cust_clock");
    private static final Instant T0 = Instant.parse("2026-10-08T10:00:00Z");

    /** Minimal settable clock so the test can advance time explicitly. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private DefaultUsageMeteringEngine engineWith(Clock clock) {
        InMemoryMeterDefinitionRepository definitions = new InMemoryMeterDefinitionRepository();
        definitions.saveDefinition(MeterDefinition.sum("STORAGE_BYTES", "Storage"));
        return new DefaultUsageMeteringEngine(
            new InMemoryIdempotencyStore(),
            new InMemoryMeterEventRepository(),
            new InMemoryMeterAggregationRepository(),
            definitions,
            Optional.of(Duration.ofDays(7)),
            clock
        );
    }

    private MeterEvent event(String eventId, Instant timestamp) {
        return MeterEvent.builder()
            .eventId(eventId)
            .idempotencyKey("idem_" + eventId)
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal("100"))
            .timestamp(timestamp)
            .build();
    }

    @Test
    @DisplayName("Late-event verdict is a pure function of the injected clock")
    void lateEventDecisionIsDeterministicUnderFixedClock() {
        // Event 8 days before T0: too old for a 7-day lateness allowance.
        IngestionResult tooOld = engineWith(Clock.fixed(T0, ZoneOffset.UTC))
            .ingest(event("evt_old", T0.minus(Duration.ofDays(8))));
        assertThat(tooOld.isRejectedLate()).isTrue();

        // Same event, same clock, different run: the verdict is identical, not flaky.
        IngestionResult tooOldAgain = engineWith(Clock.fixed(T0, ZoneOffset.UTC))
            .ingest(event("evt_old2", T0.minus(Duration.ofDays(8))));
        assertThat(tooOldAgain.isRejectedLate()).isTrue();
        assertThat(tooOldAgain.message()).contains("older than maximum allowed lateness");

        // Event 6 days before T0: inside the allowance.
        IngestionResult recent = engineWith(Clock.fixed(T0, ZoneOffset.UTC))
            .ingest(event("evt_recent", T0.minus(Duration.ofDays(6))));
        assertThat(recent.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("The same event flips from accepted to rejected purely by advancing the clock")
    void advancingClockMovesTheWatermark() {
        MutableClock clock = new MutableClock(T0);
        DefaultUsageMeteringEngine engine = engineWith(clock);

        Instant eventTime = T0.minus(Duration.ofDays(6));

        assertThat(engine.ingest(event("evt_a", eventTime)).isAccepted()).isTrue();

        // Push the watermark 3 days forward: the same timestamp is now outside the allowance.
        clock.advance(Duration.ofDays(3));
        IngestionResult afterAdvance = engine.ingest(event("evt_b", eventTime));
        assertThat(afterAdvance.isRejectedLate()).isTrue();
    }

    @Test
    @DisplayName("Boundary event exactly at the lateness threshold is accepted (inclusive lower bound)")
    void boundaryEventIsAccepted() {
        Instant eventTime = T0.minus(Duration.ofDays(7));
        IngestionResult result = engineWith(Clock.fixed(T0, ZoneOffset.UTC))
            .ingest(event("evt_boundary", eventTime));
        assertThat(result.isAccepted()).isTrue();
    }

    @Test
    @DisplayName("Idempotency store records clock time deterministically when no event time is supplied")
    void idempotencyStoreUsesInjectedClock() {
        MutableClock clock = new MutableClock(T0);
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);

        assertThat(store.checkAndRecord(tenantId, "no-ts", null)).isTrue();
        // The recorded value is the injected clock instant, so it is exactly removable again.
        assertThat(store.remove(tenantId, "no-ts", T0)).isTrue();
    }
}