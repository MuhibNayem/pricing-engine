package com.saas.pricing.redis.conformance;

import com.saas.pricing.metering.spi.RatingClaimStore;
import com.saas.pricing.redis.RedisRatingClaimStore;

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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the shared contract against the Redis adapter, against a real Redis 7. */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("RatingClaimStore conformance — Redis")
class RedisRatingClaimStoreConformanceTest extends RatingClaimStoreConformance {

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
    protected RatingClaimStore store() {
        connection.sync().flushall();
        return new RedisRatingClaimStore(connection, Clock.systemUTC(), Duration.ofDays(90));
    }

    /**
     * The point of compareAndSet, and the reason it cannot be done with {@code SET NX}: concurrent
     * claimers of the same window must produce exactly one winner.
     */
    @Test
    @DisplayName("concurrent claimers of one window produce exactly one winner")
    void concurrentCompareAndSetHasExactlyOneWinner() throws Exception {
        int threads = 32;
        RatingClaimStore store = store();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                // Every thread claims the same uncharged window with a different amount, as
                // concurrent rating deltas for the same window genuinely would.
                if (store.compareAndSet(ACME, "w", Optional.empty(),
                        charged("10." + String.format("%02d", index) + "00", T0))) {
                    winners.incrementAndGet();
                }
                return null;
            });
        }

        try {
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(winners.get())
            .as("exactly one of 32 concurrent claimers may win, or the window is charged repeatedly")
            .isEqualTo(1);
        assertThat(store.find(ACME, "w")).isPresent();
    }
}