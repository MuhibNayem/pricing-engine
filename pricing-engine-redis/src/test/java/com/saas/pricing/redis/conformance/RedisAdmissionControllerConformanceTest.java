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

    @Override
    protected AdmissionController controller(ManualClock clock) {
        // Flush: the bucket lives in Redis, so without this one test's drained quota becomes the
        // next test's starting state and the suite fails in a way that looks like a limiter bug.
        connection.sync().flushall();
        return new RedisAdmissionController(connection.sync(), 5, Duration.ofSeconds(1), 5,
            1_000_000, Duration.ofMillis(50), clock::nanos);
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
        ManualClock clock = new ManualClock();

        // Deliberately five separate controller instances standing in for five pods. No shared JVM
        // state between them except the Redis they all talk to.
        List<AdmissionController> pods = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            pods.add(new RedisAdmissionController(connection.sync(), 5, Duration.ofSeconds(1), 5,
                1_000_000, Duration.ofMillis(50), clock::nanos));
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
        ManualClock clock = new ManualClock();
        AdmissionController controller = new RedisAdmissionController(connection.sync(), 5,
            Duration.ofSeconds(1), 5, 1_000_000, Duration.ofMillis(50), clock::nanos);

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