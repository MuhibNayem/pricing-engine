package com.saas.pricing.metering.engine;

import com.saas.pricing.core.engine.DefaultPricingEngine;
import com.saas.pricing.core.engine.WalletDrawdownEngine;
import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.CustomerId;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.wallet.CreditGrant;
import com.saas.pricing.core.model.wallet.Wallet;
import com.saas.pricing.core.model.wallet.WalletDrawdownResult;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.impl.InMemoryCurrencyExchangeProvider;
import com.saas.pricing.core.spi.impl.InMemoryRateCardRepository;
import com.saas.pricing.core.spi.impl.InMemoryWalletRepository;
import com.saas.pricing.core.spi.impl.RuleBasedTaxProvider;
import com.saas.pricing.metering.model.MeterAggregation;
import com.saas.pricing.metering.model.MeterDefinition;
import com.saas.pricing.metering.model.MeterEvent;
import com.saas.pricing.metering.model.TimeWindow;
import com.saas.pricing.metering.spi.impl.InMemoryIdempotencyStore;
import com.saas.pricing.metering.spi.impl.InMemoryMeterAggregationRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterDefinitionRepository;
import com.saas.pricing.metering.spi.impl.InMemoryMeterEventRepository;
import com.saas.pricing.metering.stream.AsyncRatingTriggerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency regression suite: wallet-charge idempotency, cache/ingest coherence, and ingestion
 * throughput.
 *
 * <p>These tests deliberately use only the pre-existing public API so that the same file can be
 * executed against the pre-fix and post-fix implementations to produce comparable probe numbers.</p>
 */
class MeteringConcurrencyTest {

    private static final int CONTENDER_THREADS = 200;

    private final TenantId tenantId = TenantId.of("tenant_conc");
    private final CustomerId customerId = CustomerId.of("cust_conc");
    private final PlanCode planCode = PlanCode.of("CONC_PLAN");
    private final Instant baseTime = Instant.parse("2026-10-08T10:00:00Z");

    private InMemoryMeterEventRepository eventRepository;
    private InMemoryMeterAggregationRepository aggregationRepository;
    private InMemoryMeterDefinitionRepository definitionRepository;
    private DefaultUsageMeteringEngine engine;
    private AsyncRatingTriggerService ratingService;
    private InMemoryWalletRepository walletRepository;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        eventRepository = new InMemoryMeterEventRepository();
        aggregationRepository = new InMemoryMeterAggregationRepository();
        definitionRepository = new InMemoryMeterDefinitionRepository();
        definitionRepository.saveDefinition(MeterDefinition.sum("BYTES_SENT", "Bytes sent"));

        engine = new DefaultUsageMeteringEngine(
            new InMemoryIdempotencyStore(),
            eventRepository,
            aggregationRepository,
            definitionRepository,
            Optional.empty()
        );

        var rateCardRepo = new InMemoryRateCardRepository();
        rateCardRepo.save(RateCard.of(
            "rc_conc", tenantId, planCode, 1, Instant.EPOCH,
            List.of(RatePlanItem.of("BYTES_SENT", "Bytes",
                PricingModel.PerUnitModel.of(new BigDecimal("0.01")), CurrencyUnit.USD))
        ));
        DefaultPricingEngine pricingEngine = new DefaultPricingEngine(
            rateCardRepo,
            new InMemoryCurrencyExchangeProvider(),
            new RuleBasedTaxProvider(),
            AuditSink.noOp(),
            null
        );

        walletRepository = new InMemoryWalletRepository();
        walletRepository.save(Wallet.of(
            "w_conc", tenantId, customerId, CurrencyUnit.USD,
            List.of(CreditGrant.prepaid("grant_1", "w_conc", "Credits", new BigDecimal("100.00"), BigDecimal.ONE, Instant.EPOCH))
        ));

        ratingService = new AsyncRatingTriggerService(engine, pricingEngine, walletRepository, new WalletDrawdownEngine());
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        ratingService.close();
    }

    // ---------------------------------------------------------------------
    // DEFECT 1 - repeat rating must not double-charge the wallet
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("200 concurrent threads on the same window and key charge the wallet exactly once")
    void concurrentSameWindowSameKeyChargesExactlyOnce() throws Exception {
        TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));
        // 300 bytes @ 0.01 = $3.00 -> 3 credits
        engine.ingest(event("evt_charge", "idem_charge", "BYTES_SENT", "300", baseTime.plusSeconds(30)));

        CountDownLatch start = new CountDownLatch(1);
        List<Future<WalletDrawdownResult>> futures = new ArrayList<>(CONTENDER_THREADS);
        for (int i = 0; i < CONTENDER_THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return ratingService
                    .aggregateRateAndDrawdownAsync(tenantId, customerId, planCode, window, CurrencyUnit.USD)
                    .join();
            }));
        }

        start.countDown();

        List<WalletDrawdownResult> results = new ArrayList<>(CONTENDER_THREADS);
        for (Future<WalletDrawdownResult> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }

        Wallet updated = walletRepository.findWallet(tenantId, customerId).orElseThrow();

        System.out.println("[PROBE defect-1] threads=" + CONTENDER_THREADS
            + " ledgerEntries=" + walletRepository.findTransactions("w_conc").size()
            + " remainingCredits=" + updated.grants().getFirst().remainingCredits());

        // Exactly one charge: one ledger entry, 100 - 3 = 97 credits left.
        assertThat(walletRepository.findTransactions("w_conc")).hasSize(1);
        assertThat(updated.grants().getFirst().remainingCredits()).isEqualByComparingTo("97.00");

        // Every caller must have received the very same original result.
        var original = results.getFirst();
        for (var result : results) {
            assertThat(result).isSameAs(original);
            assertThat(result.totalCreditsDrawn()).isEqualByComparingTo("3.00");
        }
    }

    @Test
    @DisplayName("Sequential repeat of the same window charge is also deduplicated and returns the original result")
    void sequentialRepeatChargeReturnsOriginalResult() {
        TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofHours(1)));
        engine.ingest(event("evt_seq", "idem_seq", "BYTES_SENT", "300", baseTime.plusSeconds(30)));

        var first = ratingService
            .aggregateRateAndDrawdownAsync(tenantId, customerId, planCode, window, CurrencyUnit.USD)
            .join();
        var second = ratingService
            .aggregateRateAndDrawdownAsync(tenantId, customerId, planCode, window, CurrencyUnit.USD)
            .join();

        assertThat(walletRepository.findTransactions("w_conc")).hasSize(1);
        assertThat(second).isSameAs(first);
        assertThat(walletRepository.findWallet(tenantId, customerId).orElseThrow()
            .grants().getFirst().remainingCredits()).isEqualByComparingTo("97.00");
    }

    // ---------------------------------------------------------------------
    // DEFECT 2 - stale aggregation cache must not mask a concurrent ingest
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Concurrent ingest + aggregate never leaves a stale under-billed cache entry")
    void concurrentIngestAndAggregateNeverUnderBills() throws Exception {
        int trials = 400;
        AtomicInteger underBilled = new AtomicInteger();

        for (int trial = 0; trial < trials; trial++) {
            final int trialIndex = trial;
            // Each trial uses a distinct window so trials cannot interfere with each other.
            TimeWindow window = TimeWindow.of(baseTime.plus(Duration.ofDays(trialIndex)),
                baseTime.plus(Duration.ofDays(trialIndex + 1)));

            engine.ingest(event("evt_a_" + trialIndex, "idem_a_" + trialIndex, "BYTES_SENT", "1",
                baseTime.plus(Duration.ofDays(trialIndex)).plusSeconds(10)));

            AtomicBoolean ingesting = new AtomicBoolean(true);
            CyclicBarrier barrier = new CyclicBarrier(2);

            Future<?> aggregator = pool.submit(() -> {
                awaitBarrier(barrier);
                while (ingesting.get()) {
                    engine.aggregate(tenantId, Optional.of(customerId), "BYTES_SENT", window);
                }
            });
            Future<?> ingestor = pool.submit(() -> {
                awaitBarrier(barrier);
                try {
                    engine.ingest(event("evt_b_" + trialIndex, "idem_b_" + trialIndex, "BYTES_SENT", "1",
                        baseTime.plus(Duration.ofDays(trialIndex)).plusSeconds(20)));
                } finally {
                    ingesting.set(false);
                }
            });

            aggregator.get(60, TimeUnit.SECONDS);
            ingestor.get(60, TimeUnit.SECONDS);

            MeterAggregation finalAggregation =
                engine.aggregate(tenantId, Optional.of(customerId), "BYTES_SENT", window);

            // Truth is 2.0. Anything less is a masked event = lost revenue.
            if (finalAggregation.aggregatedValue().compareTo(new BigDecimal("2")) != 0) {
                underBilled.incrementAndGet();
            }
        }

        System.out.println("[PROBE defect-2] trials=" + trials + " underBilled=" + underBilled.get());
        assertThat(underBilled.get()).as("aggregations under-reporting billable usage").isZero();
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------
    // DEFECT 4 - ingestion must not be quadratic
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Ingesting 20k events completes and dedupe stays correct")
    void ingestTwentyThousandEvents() {
        int eventCount = 20_000;
        TimeWindow window = TimeWindow.of(baseTime, baseTime.plus(Duration.ofDays(2)));
        Instant windowStart = baseTime;

        long startedAt = System.nanoTime();
        for (int i = 0; i < eventCount; i++) {
            // Keep every event inside [windowStart, windowEnd).
            Instant ts = windowStart.plusSeconds(i % 100_000);
            IngestionOutcome outcome = ingestQuietly(event("bulk_" + i, "idem_bulk_" + i, "BYTES_SENT", "1", ts));
            if (outcome == IngestionOutcome.REJECTED) {
                throw new IllegalStateException("unexpected rejection at i=" + i);
            }
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        System.out.println("[PROBE defect-4] events=" + eventCount + " elapsedMs=" + elapsedMillis);

        // Correctness, not a tight timing bound: all 20k events are stored and summed exactly once.
        MeterAggregation aggregation = engine.aggregate(tenantId, Optional.of(customerId), "BYTES_SENT", window);
        assertThat(aggregation.eventCount()).isEqualTo(eventCount);
        assertThat(aggregation.aggregatedValue()).isEqualByComparingTo(BigDecimal.valueOf(eventCount));

        // Replaying every idempotency key must still be rejected as duplicate, not double-counted.
        int acceptedOnReplay = 0;
        for (int i = 0; i < eventCount; i++) {
            Instant ts = windowStart.plusSeconds(i % 100_000);
            if (ingestQuietly(event("bulk_" + i, "idem_bulk_" + i, "BYTES_SENT", "1", ts)) == IngestionOutcome.ACCEPTED) {
                acceptedOnReplay++;
            }
        }
        assertThat(acceptedOnReplay).isZero();

        MeterAggregation afterReplay = engine.aggregate(tenantId, Optional.of(customerId), "BYTES_SENT", window);
        assertThat(afterReplay.eventCount()).isEqualTo(eventCount);
        assertThat(afterReplay.aggregatedValue()).isEqualByComparingTo(BigDecimal.valueOf(eventCount));
    }

    private IngestionOutcome ingestQuietly(MeterEvent event) {
        var result = engine.ingest(event);
        return switch (result.status()) {
            case ACCEPTED -> IngestionOutcome.ACCEPTED;
            case DUPLICATE -> IngestionOutcome.DUPLICATE;
            case REJECTED_LATE -> IngestionOutcome.REJECTED;
        };
    }

    private enum IngestionOutcome { ACCEPTED, DUPLICATE, REJECTED }

    private MeterEvent event(String eventId, String idempotencyKey, String meterCode, String value, Instant ts) {
        return MeterEvent.builder()
            .eventId(eventId)
            .idempotencyKey(idempotencyKey)
            .tenantId(tenantId)
            .customerId(customerId)
            .meterCode(meterCode)
            .value(new BigDecimal(value))
            .timestamp(ts)
            .build();
    }
}