package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.AdmissionController;
import com.saas.pricing.redis.RedisAdmissionController;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Runs the shared contract against the distributed limiter, against a real Redis 7. */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("AdmissionController conformance — Redis")
class RedisAdmissionControllerConformanceTest extends AdmissionControllerConformance {

    @Container
    static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
    }

    @AfterAll
    static void disconnect() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    /** Mirrors the implementation's key layout; tests may know it, production code must not. */
    private static String bucketKey(TenantId tenantId) {
        return "aequitas:quota:{" + tenantId.value() + "}";
    }

    @Override
    protected AdmissionController controller(ManualClock clock) {
        return controller(clock, 1_000_000);
    }

    @Override
    protected AdmissionController controller(ManualClock clock, int maxConcurrency) {
        // Flush: the bucket lives in Redis, so without this one test's drained quota becomes the
        // next test's starting state and the suite fails in a way that looks like a limiter bug.
        connection.sync().flushall();
        return new RedisAdmissionController(connection.sync(), 5, Duration.ofSeconds(1), 5,
            maxConcurrency, Duration.ofMillis(50), clock);
    }

    // ---------------------------------------------------------------------------------------------
    // The clock domain. Everything below runs the constructor a host actually uses: no caller clock,
    // because that is the only way this class can be built in production.
    // ---------------------------------------------------------------------------------------------

    private AdmissionController productionController() {
        connection.sync().flushall();
        return new RedisAdmissionController(connection.sync(), 5, Duration.ofSeconds(1), 5, 1_000_000,
            Duration.ofMillis(50));
    }

    @Test
    @DisplayName("the shared bucket is stamped in Redis's epoch, not a process's uptime")
    void bucketTimestampIsServerEpochMillis() {
        AdmissionController controller = productionController();
        controller.admit(ACME).lease().close();

        String stored = connection.sync().hget(bucketKey(ACME), "updated");
        long recorded = Long.parseLong(stored);
        long wallClockNow = System.currentTimeMillis();

        // The bucket is read by every node in the cluster, so its timestamp has to be in a domain all
        // of them can interpret. This used to be System.nanoTime()/1e6 - a monotonic duration whose
        // origin is whichever machine happened to answer, so two pods a day apart in uptime disagreed
        // by 86,400,000 and a pod an hour out by NTP disagreed by 3,600,000. Either is enough to
        // rewind a shared bucket to full burst.
        //
        // Epoch millis and uptime cannot be confused: a machine would have to have been up for ~56
        // years for the two to coincide, so this fails deterministically if the domain regresses.
        assertThat(recorded)
            .as("the shared bucket must be stamped in an epoch domain every node can read, not in "
                + "a per-process monotonic origin")
            .isCloseTo(wallClockNow, within(60_000L));
    }

    @Test
    @DisplayName("production refill reads the server clock, so rewinding the bucket refills it")
    void refillUsesTheServerClock() {
        AdmissionController controller = productionController();

        for (int i = 0; i < 5; i++) {
            controller.admit(ACME).lease().close();
        }
        assertThat(controller.admit(ACME).admitted()).isFalse();

        // Age the stored instant by exactly one token's worth. Only refill computed from the same
        // clock that wrote the stamp can read this; any other domain lands nowhere near one token.
        ageBucketBy(ACME, Duration.ofMillis(200));

        assertThat(controller.admit(ACME).admitted())
            .as("200ms of server time buys exactly one token at 5/s")
            .isTrue();
        assertThat(controller.admit(ACME).admitted()).as("and only one").isFalse();
    }

    @Test
    @DisplayName("a bucket stamped in the future does not mint tokens")
    void futureTimestampDoesNotMintTokens() {
        AdmissionController controller = productionController();

        for (int i = 0; i < 5; i++) {
            controller.admit(ACME).lease().close();
        }
        pushBucketPastNow(ACME, Duration.ofHours(1));

        // Unreachable through the normal path now that one clock writes every stamp. It is reachable
        // in the ways that actually happen: a key restored from a lagging replica, or a replica
        // promoted with a clock ahead of the primary's. Either way the bucket is wrong, and a
        // limiter that answers by handing out a whole extra burst makes it worse.
        assertThat(controller.admit(ACME).admitted())
            .as("an instant ahead of now must be clamped, never subtracted from")
            .isFalse();
    }

    /** Makes the bucket look {@code by} older than it is, as if that much time had passed. */
    private void ageBucketBy(TenantId tenantId, Duration by) {
        String key = bucketKey(tenantId);
        long current = Long.parseLong(connection.sync().hget(key, "updated"));
        connection.sync().hset(key, "updated", Long.toString(current - by.toMillis()));
    }

    /** Stamps the bucket {@code by} later than now, so elapsed goes negative and must be clamped. */
    private void pushBucketPastNow(TenantId tenantId, Duration by) {
        String key = bucketKey(tenantId);
        long current = Long.parseLong(connection.sync().hget(key, "updated"));
        connection.sync().hset(key, "updated", Long.toString(current + by.toMillis()));
    }

    /**
     * The reason this implementation exists at all: a per-process limiter behind a load balancer
     * grants the tenant its quota once per node.
     */
    @Test
    @DisplayName("several independent nodes share one quota, not one each")
    void quotaIsSharedAcrossNodes() throws Exception {
        int nodes = 5;
        connection.sync().flushall();

        // Deliberately five separate controller instances standing in for five pods, each built the
        // way a host builds one. No shared JVM state between them except the Redis they all talk to.
        List<AdmissionController> pods = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            pods.add(new RedisAdmissionController(connection.sync(), 5, Duration.ofSeconds(1), 5,
                1_000_000, Duration.ofMillis(50)));
        }

        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(nodes);

        List<Future<?>> futures = new ArrayList<>();
        for (AdmissionController pod : pods) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 5; i++) {
                    AdmissionController.Admission admission = pod.admit(ACME);
                    if (admission.admitted()) {
                        admitted.incrementAndGet();
                        admission.lease().close();
                    }
                }
                return null;
            }));
        }

        try {
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get())
            .as("5 nodes x 5 requests against one shared burst of 5 must admit exactly 5, not 25")
            .isEqualTo(5);
    }

    /** A tenant's quota must not be consumed by another tenant hammering a shared limiter. */
    @Test
    @DisplayName("tenants do not consume each other's quota under concurrency")
    void tenantsAreIsolatedUnderConcurrency() throws Exception {
        connection.sync().flushall();
        AdmissionController controller = new RedisAdmissionController(connection.sync(), 5,
            Duration.ofSeconds(1), 5, 1_000_000, Duration.ofMillis(50));

        TenantId[] tenants = new TenantId[5];
        for (int i = 0; i < tenants.length; i++) {
            tenants[i] = new TenantId("t" + i);
        }

        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tenants.length);

        List<Future<?>> futures = new ArrayList<>();
        for (TenantId tenant : tenants) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 5; i++) {
                    AdmissionController.Admission admission = controller.admit(tenant);
                    if (admission.admitted()) {
                        admitted.incrementAndGet();
                        admission.lease().close();
                    }
                }
                return null;
            }));
        }

        try {
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get())
            .as("5 tenants x a burst of 5 each must admit 25 in total")
            .isEqualTo(25);
    }
}