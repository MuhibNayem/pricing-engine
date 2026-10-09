package com.saas.pricing.metering.engine;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.IngestionResult;
import com.saas.pricing.metering.model.MeterDefinition;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.metering.spi.MeterEventRepository;
import com.saas.pricing.metering.spi.impl.InMemoryIdempotencyStore;
import com.saas.pricing.metering.spi.impl.InMemoryMeterAggregationRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterDefinitionRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A repository whose {@code saveEvent} fails a configurable number of times, simulating a transient
 * database error occurring <em>after</em> the idempotency key has already been claimed.
 */
final class FlakyMeterEventRepository implements MeterEventRepository {

    private final MeterEventRepository delegate = new InMemoryMeterEventRepository();
    private final AtomicInteger failuresRemaining;
    final AtomicInteger saveAttempts = new AtomicInteger();

    FlakyMeterEventRepository(int failures) {
        this.failuresRemaining = new AtomicInteger(failures);
    }

    @Override
    public boolean saveEvent(MeterEvent event) {
        saveAttempts.incrementAndGet();
        if (failuresRemaining.getAndDecrement() > 0) {
            throw new IllegalStateException("simulated transient storage failure");
        }
        return delegate.saveEvent(event);
    }

    @Override
    public List<MeterEvent> findEvents(TenantId tenantId, Optional<CustomerId> customerId, String meterCode, Instant from, Instant to) {
        return delegate.findEvents(tenantId, customerId, meterCode, from, to);
    }

    @Override
    public List<MeterEvent> findAllEvents(TenantId tenantId, Optional<CustomerId> customerId, Instant from, Instant to) {
        return delegate.findAllEvents(tenantId, customerId, from, to);
    }
}

/**
 * DEFECT 3 - an idempotency key consumed by an ingest that then failed must be released, otherwise
 * a legitimate retry is permanently rejected as a duplicate and the usage is never billed.
 */
class IdempotencyRollbackTest {

    private final TenantId tenantId = TenantId.of("tenant_rb");
    private final CustomerId customerId = CustomerId.of("cust_rb");
    private final Instant baseTime = Instant.parse("2026-10-08T10:00:00Z");
    private final TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));

    private InMemoryIdempotencyStore idempotencyStore;
    private InMemoryMeterDefinitionRepository definitionRepository;

    private DefaultUsageMeteringEngine engineWith(MeterEventRepository repository) {
        idempotencyStore = new InMemoryIdempotencyStore();
        definitionRepository = new InMemoryMeterDefinitionRepository();
        definitionRepository.saveDefinition(MeterDefinition.sum("STORAGE_BYTES", "Storage"));
        return new DefaultUsageMeteringEngine(
            idempotencyStore,
            repository,
            new InMemoryMeterAggregationRepository(),
            definitionRepository,
            Optional.empty()
        );
    }

    private MeterEvent event(String eventId, String idempotencyKey, String value) {
        return MeterEvent.builder()
            .eventId(eventId)
            .idempotencyKey(idempotencyKey)
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("STORAGE_BYTES")
            .value(new BigDecimal(value))
            .timestamp(baseTime.plusSeconds(10))
            .build();
    }

    @Test
    @DisplayName("Key consumed by a failed save is released so the retry is accepted")
    void failedSaveReleasesIdempotencyKey() {
        FlakyMeterEventRepository repository = new FlakyMeterEventRepository(1);
        DefaultUsageMeteringEngine engine = engineWith(repository);

        MeterEvent e = event("evt_rb", "idem_rb", "100");

        // First attempt fails inside the repository, after the key was claimed.
        assertThatThrownBy(() -> engine.ingest(e))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simulated transient storage failure");

        // The key must no longer be marked as seen, otherwise the retry is lost forever.
        assertThat(idempotencyStore.isDuplicate(tenantId, "idem_rb")).isFalse();

        // The retry must succeed and be counted exactly once.
        IngestionResult retry = engine.ingest(e);
        assertThat(retry.isAccepted()).isTrue();
        assertThat(retry.isDuplicate()).isFalse();

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "STORAGE_BYTES", window);
        assertThat(aggregation.eventCount()).isEqualTo(1);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("A successful save is not rolled back: a genuine replay stays a duplicate")
    void successfulSaveKeepsKeyConsumed() {
        FlakyMeterEventRepository repository = new FlakyMeterEventRepository(0);
        DefaultUsageMeteringEngine engine = engineWith(repository);

        MeterEvent e = event("evt_keep", "idem_keep", "100");

        assertThat(engine.ingest(e).isAccepted()).isTrue();
        assertThat(idempotencyStore.isDuplicate(tenantId, "idem_keep")).isTrue();

        // Replay of a successfully ingested event must remain a duplicate.
        assertThat(engine.ingest(e).isDuplicate()).isTrue();

        MeterAggregation aggregation =
            engine.aggregate(tenantId, Optional.of(customerId), "STORAGE_BYTES", window);
        assertThat(aggregation.eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Rollback only removes the key when it still holds the exact recorded value")
    void conditionalRemoveDoesNotStealAnotherWritersKey() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        Instant recordedAt = Instant.parse("2026-10-08T10:00:00Z");

        assertThat(store.checkAndRecord(tenantId, "idem_cas", recordedAt)).isTrue();

        // A stale rollback attempt carrying a different value must not delete the key.
        assertThat(store.remove(tenantId, "idem_cas", recordedAt.plusSeconds(60))).isFalse();
        assertThat(store.isDuplicate(tenantId, "idem_cas")).isTrue();

        // The exact value releases it.
        assertThat(store.remove(tenantId, "idem_cas", recordedAt)).isTrue();
        assertThat(store.isDuplicate(tenantId, "idem_cas")).isFalse();
    }
}