package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.model.MeterEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEFECT 4 - the in-memory event repository must provide O(1) amortised deduplication and
 * deterministic ordering, while preserving the original duplicate semantics exactly.
 */
class InMemoryMeterEventRepositoryTest {

    private final TenantId tenantId = TenantId.of("tenant_repo");
    private final CustomerId customerId = CustomerId.of("cust_repo");
    private final Instant base = Instant.parse("2026-10-08T10:00:00Z");

    private InMemoryMeterEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryMeterEventRepository();
    }

    private MeterEvent event(String eventId, String idempotencyKey, Instant ts) {
        return MeterEvent.builder()
            .eventId(eventId)
            .idempotencyKey(idempotencyKey)
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("BYTES")
            .value(BigDecimal.ONE)
            .timestamp(ts)
            .build();
    }

    @Test
    @DisplayName("Duplicate eventId is rejected")
    void duplicateEventIdRejected() {
        assertThat(repository.saveEvent(event("evt_1", "idem_1", base))).isTrue();
        assertThat(repository.saveEvent(event("evt_1", "idem_2", base.plusSeconds(1)))).isFalse();
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Duplicate idempotencyKey under a different eventId is rejected")
    void duplicateIdempotencyKeyRejected() {
        assertThat(repository.saveEvent(event("evt_1", "idem_same", base))).isTrue();
        assertThat(repository.saveEvent(event("evt_2", "idem_same", base.plusSeconds(1)))).isFalse();
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Same identity under a different tenant is not a duplicate")
    void dedupeIsScopedPerTenant() {
        TenantId otherTenant = TenantId.of("tenant_other");
        assertThat(repository.saveEvent(event("evt_1", "idem_1", base))).isTrue();

        MeterEvent otherTenantEvent = MeterEvent.builder()
            .eventId("evt_1")
            .idempotencyKey("idem_1")
            .tenantId(otherTenant)
            .customerId(customerId)
            .meterCode("BYTES")
            .value(BigDecimal.ONE)
            .timestamp(base)
            .build();

        assertThat(repository.saveEvent(otherTenantEvent)).isTrue();
        assertThat(repository.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Query results are deterministically ordered by (timestamp, eventId)")
    void queryOrderIsDeterministic() {
        Instant t = base;
        // Inserted in a deliberately shuffled order relative to (timestamp, eventId).
        repository.saveEvent(event("evt_c", "idem_c", t.plusSeconds(1)));
        repository.saveEvent(event("evt_a", "idem_a", t));
        repository.saveEvent(event("evt_b", "idem_b", t));

        List<String> ids = repository.findEvents(tenantId, Optional.of(customerId), "BYTES", t, t.plusSeconds(60))
            .stream().map(MeterEvent::eventId).toList();

        assertThat(ids).containsExactly("evt_a", "evt_b", "evt_c");
    }

    @Test
    @DisplayName("Interval filtering is half-open on [from, to)")
    void intervalFilteringIsHalfOpen() {
        repository.saveEvent(event("evt_start", "idem_s", base));
        repository.saveEvent(event("evt_end", "idem_e", base.plusSeconds(60)));
        repository.saveEvent(event("evt_mid", "idem_m", base.plusSeconds(30)));

        List<MeterEvent> found =
            repository.findEvents(tenantId, Optional.of(customerId), "BYTES", base, base.plusSeconds(60));

        assertThat(found).extracting(MeterEvent::eventId).containsExactly("evt_start", "evt_mid");
    }

    @Test
    @DisplayName("Meter code matching is case-insensitive")
    void meterCodeMatchingIsCaseInsensitive() {
        repository.saveEvent(event("evt_1", "idem_1", base));
        assertThat(repository.findEvents(tenantId, Optional.of(customerId), "bytes", base, base.plusSeconds(60)))
            .hasSize(1);
    }

    @Test
    @DisplayName("Concurrent inserts of the same identity yield exactly one stored event")
    void concurrentDuplicateInsertsStoreOneEvent() throws Exception {
        int threads = 64;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (repository.saveEvent(event("evt_shared", "idem_shared", base))) {
                            accepted.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent distinct inserts all survive without loss")
    void concurrentDistinctInsertsAreAllStored() throws Exception {
        int threads = 16;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> stored = new ConcurrentSkipListSet<>();

        try {
            for (int t = 0; t < threads; t++) {
                final int threadIndex = t;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String id = "evt_" + threadIndex + "_" + i;
                        if (repository.saveEvent(event(id, "idem_" + id, base.plusSeconds(i)))) {
                            stored.add(id);
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(stored).hasSize(threads * perThread);
        assertThat(repository.size()).isEqualTo(threads * perThread);
    }

    @Test
    @DisplayName("clear() empties both indexes so the repository can be reused")
    void clearEmptiesIndexes() {
        repository.saveEvent(event("evt_1", "idem_1", base));
        repository.saveEvent(event("evt_2", "idem_2", base.plusSeconds(1)));

        repository.clear();
        assertThat(repository.size()).isZero();

        // The dedupe index must be cleared too: re-saving the same identity must be accepted again.
        assertThat(repository.saveEvent(event("evt_1", "idem_1", base))).isTrue();
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("findAllEvents spans meters but still filters tenant, customer and interval")
    void findAllEventsFilters() {
        Instant from = base;
        Instant to = base.plusSeconds(60);

        repository.saveEvent(event("evt_1", "idem_1", from.plusSeconds(1)));

        MeterEvent otherMeter = MeterEvent.builder()
            .eventId("evt_other_meter")
            .idempotencyKey("idem_other_meter")
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode("REQUESTS")
            .value(BigDecimal.ONE)
            .timestamp(from.plusSeconds(2))
            .build();
        repository.saveEvent(otherMeter);

        MeterEvent outside = event("evt_outside", "idem_outside", to.plusSeconds(1));
        repository.saveEvent(outside);

        assertThat(repository.findAllEvents(tenantId, Optional.of(customerId), from, to))
            .extracting(MeterEvent::eventId)
            .containsExactlyInAnyOrder("evt_1", "evt_other_meter");

        assertThat(repository.findAllEvents(TenantId.of("tenant_nope"), Optional.empty(), from, to)).isEmpty();
    }

    @Test
    @DisplayName("Bulk ingestion of 20k events stores every event exactly once")
    void bulkIngestionStoresAllEvents() {
        int count = 20_000;
        for (int i = 0; i < count; i++) {
            String id = "bulk_" + i;
            assertThat(repository.saveEvent(event(id, "idem_" + i, base.plusSeconds(i % 1000)))).isTrue();
        }

        assertThat(repository.size()).isEqualTo(count);
        assertThat(repository.findEvents(tenantId, Optional.of(customerId), "BYTES",
            base, base.plus(Duration.ofSeconds(2000)))).hasSize(count);

        // Full replay must be rejected by the dedupe index.
        for (int i = 0; i < count; i++) {
            assertThat(repository.saveEvent(event("bulk_" + i, "idem_" + i, base.plusSeconds(i % 1000)))).isFalse();
        }
        assertThat(repository.size()).isEqualTo(count);
    }
}