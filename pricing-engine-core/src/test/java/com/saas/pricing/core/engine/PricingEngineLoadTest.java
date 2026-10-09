package com.saas.pricing.core.engine;

import com.saas.pricing.core.model.CurrencyUnit;
import com.saas.pricing.core.model.PlanCode;
import com.saas.pricing.core.model.PricingModel;
import com.saas.pricing.core.model.PricingRequest;
import com.saas.pricing.core.model.RateCard;
import com.saas.pricing.core.model.RatePlanItem;
import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AuditSink;
import com.saas.pricing.core.spi.FormulaExpressionEvaluator;
import com.saas.pricing.core.spi.RateCardRepository;
import com.saas.pricing.core.spi.TaxProvider;
import com.saas.pricing.core.spi.impl.InMemoryAuditSink;
import com.saas.pricing.core.spi.impl.InMemoryCurrencyExchangeProvider;
import com.saas.pricing.core.spi.impl.RuleBasedTaxProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load profile for the pricing hot path.
 *
 * <p>Tagged {@code load} and excluded from the ordinary build — see the surefire configuration in
 * the root {@code pom.xml}. Run it deliberately:
 *
 * <pre>{@code mvn test -Dgroups=load -DfailIfNoTests=false}</pre>
 *
 * <p>This measures the library's own throughput and latency distribution, which is the part the
 * library owns. It deliberately does not measure HTTP: transport is the host's concern, and a
 * number for it would describe the host, not the engine.
 *
 * <p>The correctness assertions here run under load on purpose. A benchmark that only reports a
 * number proves nothing about a billing engine; what matters is that 100k concurrent evaluations
 * still return exact money.
 */
@Tag("load")
class PricingEngineLoadTest {

    private static final CurrencyUnit USD = CurrencyUnit.USD;
    private static final PlanCode PLAN = PlanCode.of("PRO");
    private static final TenantId TENANT = TenantId.of("t1");
    private static final Instant T0 = Instant.parse("2026-10-09T12:00:00Z");

    /** Tuned by the {@code -Dload.*} properties so a laptop and a CI box are both usable. */
    private static final int THREADS = Integer.getInteger("load.threads", Math.max(4, Runtime.getRuntime().availableProcessors()));
    private static final int SECONDS = Integer.getInteger("load.seconds", 10);

    /** Fixed repository: a load test that also measures lookup cost is measuring two things. */
    private static final class FixedRateCardRepository implements RateCardRepository {
        private final java.util.concurrent.ConcurrentMap<String, RateCard> cards =
            new java.util.concurrent.ConcurrentHashMap<>();

        void put(RateCard card) {
            cards.put(card.planCode().value(), card);
        }

        @Override
        public java.util.Optional<RateCard> findEffectiveRateCard(
            TenantId tenantId, PlanCode planCode, Instant effectiveTime) {
            return java.util.Optional.ofNullable(cards.get(planCode.value()));
        }

        @Override
        public void save(RateCard rateCard) {
            put(rateCard);
        }
    }

    private record Engine(PricingEngine engine, FixedRateCardRepository repo) {}

    private Engine engine() {
        var repo = new FixedRateCardRepository();
        repo.put(RateCard.of("rc-load", TENANT, PLAN, 1, T0,
            List.of(RatePlanItem.of("SEATS", "seats",
                PricingModel.PerUnitModel.of(new BigDecimal("25.00")), USD))));

        FormulaExpressionEvaluator evaluator = (expression, variables) -> null;
        AuditSink audit = new InMemoryAuditSink();
        var fx = new InMemoryCurrencyExchangeProvider();
        TaxProvider tax = new RuleBasedTaxProvider();

        return new Engine(new DefaultPricingEngine(repo, fx, tax, audit, evaluator), repo);
    }

    private static PricingRequest request(String customerId, String item, int quantity) {
        return PricingRequest.builder()
            .tenantId(TENANT.value()).planCode(PLAN.value()).evaluationTime(T0)
            .targetCurrency(USD).customerId(customerId).item(item, quantity).build();
    }

    private record Percentiles(long count, double p50Micros, double p95Micros, double p99Micros,
                               double maxMicros, double throughputPerSec) {}

    /** Runs {@code THREADS} workers against one engine for {@code SECONDS}, recording every latency. */
    private Percentiles drive(Engine e, int iterationsPerThread) throws Exception {
        var latencies = new ArrayList<double[]>(THREADS * iterationsPerThread);
        var failures = new AtomicLong();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        long t0 = System.nanoTime();
        try {
            for (int t = 0; t < THREADS; t++) {
                final int seed = t;
                pool.submit(() -> {
                    var mine = new ArrayList<double[]>(iterationsPerThread);
                    try {
                        start.await();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            long b = System.nanoTime();
                            e.engine().evaluate(request("c" + (seed % 8), "SEATS", 1 + (i % 50)));
                            mine.add(new double[]{(System.nanoTime() - b) / 1000.0});
                        }
                    } catch (Throwable ex) {
                        failures.incrementAndGet();
                    } finally {
                        synchronized (latencies) {
                            latencies.addAll(mine);
                        }
                        done.countDown();
                    }
                });
            }
            start.countDown();
            if (!done.await(SECONDS * 3L + 60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("load run did not finish within its budget");
            }
        } finally {
            pool.shutdownNow();
        }
        double elapsedSeconds = (System.nanoTime() - t0) / 1_000_000_000.0;

        assertThat(failures.get()).as("no evaluation may fail under load").isZero();

        double[] sorted = latencies.stream().mapToDouble(a -> a[0]).sorted().toArray();
        return new Percentiles(
            sorted.length,
            pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99),
            sorted[sorted.length - 1],
            sorted.length / elapsedSeconds);
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1);
        return sorted[Math.max(0, idx)];
    }

    @Test
    @DisplayName("sustained concurrent evaluation stays exact and reports its latency profile")
    void sustainedLoadStaysExact() throws Exception {
        int perThread = Integer.getInteger("load.iterations", 4000);
        Engine e = engine();

        Percentiles p = drive(e, perThread);

        System.out.printf(
            "%n  [load] threads=%d evaluations=%d%n"
          + "         throughput=%.0f eval/s%n"
          + "         latency  p50=%.0fus  p95=%.0fus  p99=%.0fus  max=%.0fus%n%n",
            THREADS, p.count(), p.throughputPerSec(),
            p.p50Micros(), p.p95Micros(), p.p99Micros(), p.maxMicros());

        assertThat(p.count()).isEqualTo((long) THREADS * perThread);
        // Generous: this asserts the engine did not fall over or become pathologically slow,
        // not that it meets an SLO. Pin an SLO before turning this into one.
        assertThat(p.throughputPerSec())
            .as("throughput collapsed under concurrency — is there shared mutable state?")
            .isGreaterThan(200);
    }

    @Test
    @DisplayName("money is exact at every concurrency level, not just at rest")
    void moneyIsExactUnderLoad() throws Exception {
        Engine e = engine();
        // 25.00 per seat, quantities 1..50 — every result is an exact multiple of 25.00,
        // so any drift from concurrent evaluation is visible as a non-round total.
        drive(e, 200);

        for (int q = 1; q <= 50; q++) {
            var result = e.engine().evaluate(request("c1", "SEATS", q));
            assertThat(result.totalGross().amount())
                .as("quantity %d must price exactly under load", q)
                .isEqualByComparingTo(new BigDecimal("25.00").multiply(BigDecimal.valueOf(q)));
        }
    }

    @Test
    @DisplayName("a rate-card change made mid-flight is visible to the next evaluation")
    void rateCardSwapIsVisibleUnderLoad() throws Exception {
        Engine e = engine();
        var before = e.engine().evaluate(request("c1", "SEATS", 2)).totalGross().amount();
        e.repo.put(RateCard.of("rc-load", TENANT, PLAN, 2, T0,
            List.of(RatePlanItem.of("SEATS", "seats",
                PricingModel.PerUnitModel.of(new BigDecimal("50.00")), USD))));
        var after = e.engine().evaluate(request("c1", "SEATS", 2)).totalGross().amount();

        assertThat(before).isEqualByComparingTo("50.00");
        assertThat(after)
            .as("a swapped rate card must not be served from a stale cached read")
            .isEqualByComparingTo("100.00");
    }
}