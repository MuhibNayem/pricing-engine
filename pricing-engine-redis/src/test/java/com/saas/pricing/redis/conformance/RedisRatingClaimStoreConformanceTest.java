package com.saas.pricing.redis.conformance;

import com.saas.pricing.metering.spi.RatingClaimStore;
import com.saas.pricing.metering.spi.RatingClaimStore.Charged;
import com.saas.pricing.redis.RedisRatingClaimStore;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * The write path must be indivisible, and the cheapest way to prove that is to count commands.
     *
     * <p>{@code record} used to be three {@code HSET}s and a {@code PEXPIRE}. Each is atomic, so a
     * reader can never see a half-executed <em>command</em> — but nothing stops it seeing a
     * half-written <em>claim</em>, which is the thing that matters here.</p>
     */
    @Test
    @DisplayName("recording a claim is one round trip, not four")
    void recordIsASingleAtomicRoundTrip() {
        AtomicInteger commands = new AtomicInteger();
        RatingClaimStore store = new RedisRatingClaimStore(
            countingCommands(commands), Clock.systemUTC(), Duration.ofDays(90));

        store.record(ACME, "w", charged("10.00", T0));

        assertThat(commands.get())
            .as("four commands leave a window in which a concurrent compareAndSet reads a claim "
                + "with one writer's amount and another's timestamp, and swaps against a mixture "
                + "that never existed")
            .isEqualTo(1);
    }

    /**
     * The behavioural half of the same guard: whatever {@code record} does internally, no reader may
     * ever observe a claim assembled from more than one write.
     */
    @Test
    @DisplayName("a claim is never observable half-written under concurrent recording")
    void recordIsAtomicAgainstConcurrentReaders() throws Exception {
        RatingClaimStore store = store();

        // Two claims that differ in every field, so any mixture is unambiguous.
        Charged alpha = new Charged(new BigDecimal("10.00"), "USD", T0);
        Charged beta = new Charged(new BigDecimal("99.99"), "EUR", T1);

        int writers = 8;
        int readers = 4;
        int rounds = 300;
        AtomicReference<String> torn = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int index = i;
            tasks.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                for (int r = 0; r < rounds; r++) {
                    store.record(ACME, "w", (index + r) % 2 == 0 ? alpha : beta);
                }
                return null;
            });
        }
        for (int i = 0; i < readers; i++) {
            tasks.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                for (int r = 0; r < rounds; r++) {
                    store.find(ACME, "w").ifPresent(seen -> {
                        boolean whole = seen.equals(alpha) || seen.equals(beta);
                        if (!whole) {
                            torn.compareAndSet(null, "observed " + seen);
                        }
                    });
                }
                return null;
            });
        }

        try {
            start.countDown();
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(torn.get())
            .as("a claim must only ever be readable as one whole write; a mixture here is a "
                + "drawdown figure that was never charged")
            .isNull();
    }

    /** A {@link RedisCommands} that counts every command the store issues. */
    private RedisCommands<String, String> countingCommands(AtomicInteger counter) {
        RedisCommands<String, String> delegate = connection.sync();
        return (RedisCommands<String, String>) Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[]{RedisCommands.class},
            (proxy, method, args) -> {
                counter.incrementAndGet();
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}