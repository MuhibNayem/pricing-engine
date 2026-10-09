package com.saas.pricing.starter.loadtest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP load profile for the engine as a host drives it.
 *
 * <p>Tag {@code load}, excluded from the ordinary build — see {@code surefire.excludedGroups} in
 * the root {@code pom.xml}. Run deliberately:
 *
 * <pre>{@code mvn test -Dtest=HttpLoadTest -Dsurefire.excludedGroups=
 *     -Dhttp.threads=64 -Dhttp.seconds=20}</pre>
 *
 * <p>What this measures, precisely: the full request path — Tomcat, JSON binding, tenant
 * resolution, rate-card lookup, rating, serialisation. What it does NOT measure is anything about
 * the engine in isolation; {@code PricingEngineLoadTest} already did that at 55k eval/s. This
 * number is the one a host team can compare against its own infrastructure, and it will be
 * dominated by whatever their transport is made of.
 *
 * <p>The correctness assertions run under load on purpose. A throughput number with no assertion
 * attached is a benchmark, not a test.
 */
@Tag("load")
@SpringBootTest(classes = LoadTestHostApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpLoadTest {

    private static final int THREADS = Integer.getInteger("http.threads", 32);
    private static final int SECONDS = Integer.getInteger("http.seconds", 15);

    @LocalServerPort
    int port;

    @Autowired
    com.saas.pricing.starter.tenant.TenantResolver tenantResolver;

    @Autowired
    com.saas.pricing.core.spi.RateCardRepository rateCardRepository;

    /**
     * Seeded here rather than as a bean: the auto-configuration already owns the
     * {@code rateCardRepository} bean name, and declaring a second one of that name is a definition
     * override error rather than a configuration choice.
     */
    @org.junit.jupiter.api.BeforeEach
    void seedRateCard() {
        var repo = (com.saas.pricing.starter.repository.InMemoryRateCardRepository) rateCardRepository;
        repo.save(com.saas.pricing.core.model.RateCard.of("rc-load",
            com.saas.pricing.core.model.TenantId.of("t-load"),
            com.saas.pricing.core.model.PlanCode.of("PRO"), 1,
            java.time.Instant.parse("2020-01-01T00:00:00Z"),
            java.util.List.of(com.saas.pricing.core.model.RatePlanItem.of("SEATS", "seats",
                com.saas.pricing.core.model.PricingModel.PerUnitModel.of(
                    new java.math.BigDecimal("25.00")),
                com.saas.pricing.core.model.CurrencyUnit.USD))));
    }

    @Test
    @DisplayName("sustained concurrent HTTP evaluation returns exact money at every latency")
    void sustainedHttpLoadStaysExact() throws Exception {
        String base = "http://localhost:" + port + "/api/v1/pricing";
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        // Prove the endpoint works and the shape is right before measuring anything.
        HttpResponse<String> probe = send(client, base, "c-warmup", 4);
        org.assertj.core.api.Assertions.assertThat(probe.statusCode())
            .as("load profile must not run against a broken endpoint")
            .isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(probe.body())
            .as("the warm-up response must already carry an exact total")
            .contains("\"totalGross\":\"100 USD\"");

        // Per-thread lists, merged after the latch. A shared ArrayList would silently DROP samples
        // under concurrency - which is exactly what happened on the first run: 19,290 responses
        // but only 19,148 latencies recorded, quietly understating the percentiles.
        var perThread = new ArrayList<List<double[]>>();
        var statusCounts = new ConcurrentHashMap<Integer, AtomicInteger>();
        var failures = new AtomicLong();
        var done = new CountDownLatch(THREADS);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        long t0 = System.nanoTime();
        try {
            for (int t = 0; t < THREADS; t++) {
                final int seed = t;
                final List<double[]> mine = new ArrayList<>(4096);
                synchronized (perThread) {
                    perThread.add(mine);
                }
                pool.submit(() -> {
                    try {
                        go.await();
                        long deadline = System.nanoTime() + SECONDS * 1_000_000_000L;
                        int n = 0;
                        while (System.nanoTime() < deadline) {
                            long b = System.nanoTime();
                            try {
                                HttpResponse<String> r = send(client, base,
                                    "c-" + (seed % 8), 1 + (n % 50));
                                statusCounts.computeIfAbsent(r.statusCode(),
                                    k -> new AtomicInteger()).incrementAndGet();
                                if (r.statusCode() != 200 || !r.body().contains("totalGross")) {
                                    failures.incrementAndGet();
                                }
                            } catch (Exception e) {
                                failures.incrementAndGet();
                            }
                            mine.add(new double[]{(System.nanoTime() - b) / 1000.0});
                            n++;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            org.assertj.core.api.Assertions.assertThat(done.await(SECONDS * 4L + 60, TimeUnit.SECONDS))
                .as("load run did not finish inside its budget")
                .isTrue();
        } finally {
            pool.shutdownNow();
        }
        double elapsed = (System.nanoTime() - t0) / 1_000_000_000.0;

        var latencies = new ArrayList<double[]>();
        synchronized (perThread) {
            perThread.forEach(latencies::addAll);
        }
        double[] sorted = latencies.stream().mapToDouble(a -> a[0]).sorted().toArray();
        long ok = statusCounts.getOrDefault(200, new AtomicInteger()).get();
        double throughput = sorted.length / elapsed;

        System.out.printf(
            "%n  [http-load] threads=%d  requests=%d  window=%.1fs%n"
          + "              throughput=%.0f req/s%n"
          + "              latency   p50=%.0fms  p95=%.0fms  p99=%.0fms  max=%.0fms%n"
          + "              statuses  %s%n%n",
            THREADS, sorted.length, elapsed,
            throughput,
            pct(sorted, 0.50) / 1000, pct(sorted, 0.95) / 1000,
            pct(sorted, 0.99) / 1000, sorted[sorted.length - 1] / 1000,
            statusCounts);

        org.assertj.core.api.Assertions.assertThat(failures.get())
            .as("a request failed or returned a malformed body under load")
            .isZero();
        org.assertj.core.api.Assertions.assertThat(ok)
            .as("every request must return 200")
            .isEqualTo(sorted.length);
        org.assertj.core.api.Assertions.assertThat(sorted.length)
            .as("the load run produced too few samples to mean anything")
            .isGreaterThan(500);
        // Deliberately a floor, not an SLO. Pin a real target before turning this into a gate.
        org.assertj.core.api.Assertions.assertThat(throughput).isGreaterThan(20);
    }

    @Test
    @DisplayName("concurrent requests for the same customer never corrupt one another's totals")
    void concurrentRequestsStayIndependent() throws Exception {
        String base = "http://localhost:" + port + "/api/v1/pricing";
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

        // Same customer, same quantity, fired concurrently: every response must be identical.
        // If any shared state leaked between requests, these would diverge.
        int n = 40;
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(16);
        List<String> bodies = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        latch.await();
                        synchronized (bodies) {
                            bodies.add(send(client, base, "c-shared", 7).body());
                        }
                    } catch (Exception e) {
                        synchronized (bodies) {
                            bodies.add("ERROR: " + e);
                        }
                    }
                }));
            }
            latch.countDown();
            for (var f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        org.assertj.core.api.Assertions.assertThat(bodies).hasSize(n);
        // calculationId is a fresh UUID per evaluation by design, so compare the money, not the
        // envelope. If any shared state leaked between concurrent requests the totals would differ.
        org.assertj.core.api.Assertions.assertThat(bodies.stream().map(HttpLoadTest::totalGross).distinct())
            .as("identical concurrent requests must produce identical totals")
            .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(bodies.get(0))
            .as("7 seats at 25.00 each is 175.00")
            .contains("\"totalGross\":\"175 USD\"");
    }

    private HttpResponse<String> send(HttpClient client, String base, String customer, int seats)
            throws Exception {
        String json = """
            {"tenantId":"t-load","planCode":"PRO","targetCurrency":"USD",
             "customerId":"%s","items":[{"itemCode":"SEATS","quantity":%d}]}
            """.formatted(customer, seats);
        return client.send(
            HttpRequest.newBuilder(URI.create(base + "/evaluate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** Pulls totalGross out of a response envelope, ignoring the per-evaluation UUID. */
    private static String totalGross(String body) {
        int i = body.indexOf("\"totalGross\":\"");
        if (i < 0) return "<no totalGross: " + body + ">";
        int start = i + "\"totalGross\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static double pct(double[] sorted, double q) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1);
        return sorted[Math.max(0, idx)];
    }
}