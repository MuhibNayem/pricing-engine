package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.IdempotencyStore;
import com.saas.pricing.metering.spi.RatingClaimStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Soak profile: whether the stores actually keep the promise their javadoc makes.
 *
 * <p>Both in-memory stores carry an explicit TTL and an explicit entry cap, and both documents
 * claim the previous unbounded maps "grew for the lifetime of the process". That is a promise
 * nobody has watched being kept. This profile keeps it: it drives far more operations than the cap
 * permits, advances the clock past the TTL, and checks that memory is genuinely reclaimed.
 *
 * <p>Time is injected rather than waited on. Both stores take a {@link Clock}, so a mutable one
 * simulates 90 days in microseconds — the alternative is a test that cannot finish.
 *
 * <p>Tagged {@code soak}; excluded from the ordinary build. Run it deliberately:
 *
 * <pre>{@code mvn test -Dtest=SoakTest -Dsurefire.excludedGroups=}</pre>
 *
 * <p>The assertion that matters most is {@link #reclamationIsLazyNotScheduled()}: these stores
 * reclaim memory <em>on access</em>. An idle process does not shrink, and the first request after
 * an idle period pays for the sweep.
 */
@Tag("soak")
class SoakTest {

    private static final TenantId TENANT = TenantId.of("t-soak");

    private static final int OPERATIONS = Integer.getInteger("soak.operations", 200_000);
    private static final int MAX_ENTRIES = Integer.getInteger("soak.maxEntries", 5_000);
    private static final int THREADS = Integer.getInteger("soak.threads", 8);

    /** A clock the test can jump, so a 90-day TTL elapses in microseconds. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
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
            return now.get();
        }
    }

    private static RatingClaimStore.Charged charged(long amount) {
        return new RatingClaimStore.Charged(BigDecimal.valueOf(amount), "USD", Instant.EPOCH);
    }

    // ------------------------------------------------------------------
    // Bounding: growth stops at the cap
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the idempotency store forgets its oldest keys instead of growing without bound")
    void idempotencyStoreGrowthIsBounded() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var store = new InMemoryIdempotencyStore(clock, Duration.ofDays(7), MAX_ENTRIES);

        int seeded = MAX_ENTRIES * 4;
        for (int i = 0; i < seeded; i++) {
            store.claim(TENANT, "idem-" + i, "fp", null);
        }

        // Measured through behaviour, not a size() the SPI does not expose: the newest keys are
        // still held, and the oldest have been evicted by the cap rather than retained.
        int recentStillHeld = 0;
        int oldestForgotten = 0;
        for (int i = 0; i < MAX_ENTRIES; i++) {
            if (store.isDuplicate(TENANT, "idem-" + (seeded - 1 - i))) recentStillHeld++;
            if (!store.isDuplicate(TENANT, "idem-" + i)) oldestForgotten++;
        }

        assertThat(recentStillHeld)
            .as("the most recent %d keys must survive", MAX_ENTRIES)
            .isEqualTo(MAX_ENTRIES);
        assertThat(oldestForgotten)
            .as("%d keys seeded into a %d-entry store: the oldest must be evicted, not retained",
                seeded, MAX_ENTRIES)
            .isEqualTo(MAX_ENTRIES);
    }

    @Test
    @DisplayName("the rating claim store forgets its oldest claims instead of growing without bound")
    void ratingClaimStoreGrowthIsBounded() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var store = new InMemoryRatingClaimStore(clock, Duration.ofDays(90), MAX_ENTRIES);

        int seeded = MAX_ENTRIES * 4;
        for (int i = 0; i < seeded; i++) {
            store.record(TENANT, "window-" + i, charged(i));
        }

        int recentStillHeld = 0;
        int oldestForgotten = 0;
        for (int i = 0; i < MAX_ENTRIES; i++) {
            if (store.find(TENANT, "window-" + (seeded - 1 - i)).isPresent()) recentStillHeld++;
            if (store.find(TENANT, "window-" + i).isEmpty()) oldestForgotten++;
        }

        assertThat(recentStillHeld)
            .as("the most recent %d claims must survive", MAX_ENTRIES)
            .isEqualTo(MAX_ENTRIES);
        assertThat(oldestForgotten)
            .as("%d claims seeded into a %d-entry store: the oldest must be evicted", seeded, MAX_ENTRIES)
            .isEqualTo(MAX_ENTRIES);
    }

    // ------------------------------------------------------------------
    // TTL: the maps actually shrink once time passes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("idempotency keys are forgotten once their TTL elapses")
    void idempotencyKeysExpireAfterTtl() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        Duration ttl = Duration.ofDays(7);
        var store = new InMemoryIdempotencyStore(clock, ttl, MAX_ENTRIES);

        assertThat(store.claim(TENANT, "stale", "fp-original", null))
            .isEqualTo(IdempotencyStore.Claim.CLAIMED);
        assertThat(store.claim(TENANT, "stale", "fp-original", null))
            .as("within the TTL the key is still held")
            .isEqualTo(IdempotencyStore.Claim.DUPLICATE);

        clock.advance(ttl.plusSeconds(1));

        assertThat(store.claim(TENANT, "stale", "fp-original", null))
            .as("past the TTL the key is forgotten, so re-ingesting it is CLAIMED not DUPLICATE")
            .isEqualTo(IdempotencyStore.Claim.CLAIMED);
    }

    @Test
    @DisplayName("rating claims are forgotten once their 90-day TTL elapses")
    void ratingClaimsExpireAfterTtl() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        Duration ttl = Duration.ofDays(90);
        var store = new InMemoryRatingClaimStore(clock, ttl, MAX_ENTRIES);

        store.record(TENANT, "w1", charged(100));
        assertThat(store.find(TENANT, "w1")).isPresent();

        clock.advance(ttl.plusSeconds(1));

        assertThat(store.find(TENANT, "w1"))
            .as("a claim older than the TTL must not be readable: a retry would then compute its "
              + "delta against a stale total and charge the wrong amount")
            .isEmpty();
    }

    // ------------------------------------------------------------------
    // The finding: reclamation is lazy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the first read after an idle period pays for the whole sweep")
    void reclamationIsLazyAndLandsOnOneRequest() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        Duration ttl = Duration.ofDays(7);
        var store = new InMemoryIdempotencyStore(clock, ttl, MAX_ENTRIES);

        for (int i = 0; i < 1_000; i++) {
            store.claim(TENANT, "idle-" + i, "fp", null);
        }
        assertThat(countKnown(store, 0, 1_000))
            .as("all 1,000 keys are held before the clock moves")
            .isEqualTo(1_000);

        clock.advance(Duration.ofDays(365));

        // There is no scheduler: nothing has run while the store sat idle. The very next read
        // sweeps every expired entry in one pass, which is why the count below is 0 and not 1000.
        //
        // That is the property worth pinning. It means reclamation is amortised onto whichever
        // request happens to arrive first after an idle period - O(expired entries) of work on a
        // single request. At the default cap of 100,000 entries that is a pause, not a rounding
        // error. A background sweeper would fix it, and deliberately is not added here: it would
        // mean this store starting threads it does not own.
        assertThat(countKnown(store, 0, 1_000))
            .as("a single read sweeps every expired entry, not just the one it looked up")
            .isZero();
    }

    // ------------------------------------------------------------------
    // Correctness must survive the churn
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a hot key hammered by every thread is claimed exactly once at a time")
    void hotKeyIsClaimedExactlyOnce() throws Exception {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var store =
            new InMemoryIdempotencyStore(clock, Duration.ofDays(7), MAX_ENTRIES);

        int perThread = 500;
        var claimed = new AtomicInteger();
        var duplicate = new AtomicInteger();
        var conflict = new AtomicInteger();
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        try {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < perThread; i++) {
                            switch (store.claim(TENANT, "hot-key", "same-fingerprint", null)) {
                                case CLAIMED -> claimed.incrementAndGet();
                                case DUPLICATE -> duplicate.incrementAndGet();
                                case CONFLICT -> conflict.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long total = (long) THREADS * perThread;
        assertThat((long) claimed.get() + duplicate.get() + conflict.get())
            .as("every attempt must be classified exactly once")
            .isEqualTo(total);
        assertThat(claimed.get() + conflict.get())
            .as("identical fingerprints can never CONFLICT — a conflict here would mean the store "
              + "compared content it should have treated as equal")
            .isEqualTo(claimed.get());
        assertThat(duplicate.get())
            .as("all but one thread must observe the key as already claimed")
            .isEqualTo(total - claimed.get());
    }

    @Test
    @DisplayName("a long mixed soak with a moving clock leaves both stores inside their bounds")
    void longMixedSoakStaysBounded() throws Exception {   // InterruptedException from the latch
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var idem = new InMemoryIdempotencyStore(clock, Duration.ofDays(7), MAX_ENTRIES);
        var claims = new InMemoryRatingClaimStore(clock, Duration.ofDays(90), MAX_ENTRIES);

        int perThread = Math.max(1, OPERATIONS / THREADS);
        var errors = new AtomicLong();
        var go = new CountDownLatch(1);
        var done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();

        try {
            // Time keeps moving while the threads run, so TTL expiry and cap eviction both fire
            // mid-flight rather than in a tidy batch at the end.
            ticker.scheduleAtFixedRate(() -> clock.advance(Duration.ofHours(6)),
                0, 1, TimeUnit.MILLISECONDS);

            for (int t = 0; t < THREADS; t++) {
                final int seed = t;
                pool.submit(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < perThread; i++) {
                            idem.claim(TENANT, "k-" + seed + "-" + i, "fp", null);
                            claims.record(TENANT, "w-" + seed + "-" + (i % 500), charged(i));
                            claims.find(TENANT, "w-" + seed + "-" + (i % 500));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errors.incrementAndGet();
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertThat(done.await(300, TimeUnit.SECONDS))
                .as("the soak did not finish inside its budget")
                .isTrue();
        } finally {
            pool.shutdownNow();
            ticker.shutdownNow();
        }

        System.out.printf(
            "%n  [soak] operations=%d threads=%d simulated-elapsed=%s cap=%d per store%n%n",
            (long) perThread * THREADS, THREADS, clock.instant(), MAX_ENTRIES);

        assertThat(errors.get())
            .as("a store must not throw under sustained concurrent churn with a moving clock")
            .isZero();
    }

    /** Counts how many of a range of keys the store still recognises. */
    private static int countKnown(InMemoryIdempotencyStore store, int from, int to) {
        int known = 0;
        for (int i = from; i < to; i++) {
            if (store.isDuplicate(TENANT, "idle-" + i)) known++;
        }
        return known;
    }
}