package com.saas.pricing.metering.spi.impl;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.metering.spi.IdempotencyStore.Claim;
import com.saas.pricing.metering.spi.RatingClaimStore.Charged;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the invariant that makes the bounded sweep legal.
 *
 * <p>The previous stores ran an exact, complete {@code purgeExpired()} on every operation, so
 * correctness depended on the sweep visiting every entry. These tests hold expiry exact at a
 * cadence that would no longer be sufficient under that design — which is the property that allows
 * reclamation to be capped.</p>
 */
@DisplayName("In-memory store expiry is evaluated at read time")
class InMemoryStoreExpiryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TenantId TENANT = new TenantId("acme");
    private static final Duration TTL = Duration.ofDays(7);

    /** Far fewer than one sweep budget, so a complete pass genuinely cannot have happened. */
    private static final int BEYOND_ONE_SWEEP_BUDGET = 1_000;

    private static MutableClock clockAt(Instant instant) {
        return new MutableClock(instant);
    }

    @Nested
    @DisplayName("InMemoryIdempotencyStore")
    class Idempotency {

        @Test
        @DisplayName("every expired key is invisible after ONE operation, even though one sweep cannot cover them")
        void expiryIsExactBeyondOneSweepBudget() {
            var clock = clockAt(T0);
            var store = new InMemoryIdempotencyStore(clock, TTL, InMemoryIdempotencyStore.DEFAULT_MAX_ENTRIES);

            for (int i = 0; i < BEYOND_ONE_SWEEP_BUDGET; i++) {
                assertThat(store.claim(TENANT, "key-" + i, "fp-" + i, T0)).isEqualTo(Claim.CLAIMED);
            }

            clock.advance(TTL.plusSeconds(1));

            // A single isDuplicate() triggers at most one capped sweep. Every key must still read
            // as absent, because the read path checks its own deadline.
            for (int i = 0; i < BEYOND_ONE_SWEEP_BUDGET; i++) {
                assertThat(store.isDuplicate(TENANT, "key-" + i))
                    .as("key-%d must be invisible the instant its TTL passes", i)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("an expired key is re-claimable and does not answer DUPLICATE")
        void expiredKeyIsReclaimable() {
            var clock = clockAt(T0);
            var store = new InMemoryIdempotencyStore(clock, TTL, 1_000);

            assertThat(store.claim(TENANT, "k", "fp", T0)).isEqualTo(Claim.CLAIMED);
            assertThat(store.claim(TENANT, "k", "fp", T0)).isEqualTo(Claim.DUPLICATE);

            clock.advance(TTL.plusSeconds(1));

            assertThat(store.isDuplicate(TENANT, "k")).isFalse();
            assertThat(store.claim(TENANT, "k", "fp", clock.instant())).isEqualTo(Claim.CLAIMED);
        }

        @Test
        @DisplayName("a backdated event expires on schedule even though it is queued last")
        void backdatedEventExpiresOnSchedule() {
            // record time is the caller's eventTime, and metering ingests late events, so record
            // order in the queue is not monotonic with expiry. A backdated key sits at the tail but
            // expires at the same moment as everything else.
            var clock = clockAt(T0);
            var store = new InMemoryIdempotencyStore(clock, TTL, 1_000);

            store.claim(TENANT, "fresh", "fp", T0);
            store.claim(TENANT, "backdated", "fp", T0.minus(Duration.ofDays(30)));

            clock.advance(Duration.ofDays(8));

            assertThat(store.isDuplicate(TENANT, "backdated")).isFalse();
            assertThat(store.isDuplicate(TENANT, "fresh")).isFalse();
        }

        @Test
        @DisplayName("per-operation cost does not scale with entry count")
        void perOperationCostDoesNotScaleWithEntryCount() {
            double atSmall = meanIsDuplicateNanos(load(200), 60_000);
            double atLarge = meanIsDuplicateNanos(load(20_000), 60_000);

            double ratio = atLarge / Math.max(atSmall, 1.0);
            assertThat(ratio)
                .as("a 100x larger store cost %.1fx more (%.0fns -> %.0fns); an O(n) sweep would be ~100x",
                    ratio, atSmall, atLarge)
                .isLessThan(5.0);
        }

        private InMemoryIdempotencyStore load(int entries) {
            var clock = clockAt(T0);
            var store = new InMemoryIdempotencyStore(clock, TTL, InMemoryIdempotencyStore.DEFAULT_MAX_ENTRIES);
            for (int i = 0; i < entries; i++) {
                store.claim(TENANT, "key-" + i, "fp", T0);
            }
            return store;
        }

        private double meanIsDuplicateNanos(InMemoryIdempotencyStore store, int iterations) {
            for (int i = 0; i < 40_000; i++) {
                store.isDuplicate(TENANT, "key-0");   // warm the JIT
            }
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                store.isDuplicate(TENANT, "key-0");
            }
            return (double) (System.nanoTime() - start) / iterations;
        }

        @Test
        @DisplayName("repeated overwrites of one key do not let the queue grow without bound")
        void repeatedOverwritesCompactTheQueue() {
            var clock = clockAt(T0);
            var store = new InMemoryIdempotencyStore(clock, TTL, 64);

            // Each re-claim of a different key leaves the previous node behind as a tombstone.
            for (int i = 0; i < 5_000; i++) {
                store.claim(TENANT, "key-" + (i % 32), "fp-" + i, T0);
            }

            // Memory stays bounded by the cap regardless of how many tombstones accumulated.
            assertThat(store.isDuplicate(TENANT, "key-0")).isTrue();

            clock.advance(TTL.plusSeconds(1));
            assertThat(store.purgeExpired()).isPositive();
            assertThat(store.isDuplicate(TENANT, "key-0")).isFalse();
        }

        @Test
        @DisplayName("the entry cap is still enforced")
        void entryCapIsEnforced() {
            var store = new InMemoryIdempotencyStore(clockAt(T0), TTL, 128);
            for (int i = 0; i < 10_000; i++) {
                store.claim(TENANT, "key-" + i, "fp", T0);
            }
            // The oldest keys were evicted to honour the cap.
            assertThat(store.isDuplicate(TENANT, "key-0")).isFalse();
            assertThat(store.isDuplicate(TENANT, "key-9999")).isTrue();
        }

        @Test
        @DisplayName("record fencing survives reclamation")
        void recordFencingSurvivesReclamation() {
            var clock = clockAt(T0);
            var store = new InMemoryIdempotencyStore(clock, TTL, 64);

            store.claim(TENANT, "k", "fp", T0);

            assertThat(store.remove(TENANT, "k", T0.plusSeconds(1))).isFalse();
            assertThat(store.isDuplicate(TENANT, "k")).isTrue();
            assertThat(store.remove(TENANT, "k", T0)).isTrue();
            assertThat(store.isDuplicate(TENANT, "k")).isFalse();
        }

        @Test
        @DisplayName("purgeExpired is idempotent and reclaims exactly the expired keys")
        void purgeExpiredIsIdempotent() {
            var clock = clockAt(T0);
            var store = new InMemoryIdempotencyStore(clock, TTL, 1_000);

            store.claim(TENANT, "a", "fp", T0);
            store.claim(TENANT, "b", "fp", T0);

            assertThat(store.purgeExpired()).isZero();

            clock.advance(TTL.plusSeconds(1));
            store.claim(TENANT, "c", "fp", clock.instant());

            assertThat(store.purgeExpired()).isPositive();
            assertThat(store.purgeExpired()).isZero();

            // "c" was recorded after the advance, so it survives; "a" and "b" do not.
            assertThat(store.isDuplicate(TENANT, "c")).isTrue();
            assertThat(store.isDuplicate(TENANT, "a")).isFalse();
        }
    }

    @Nested
    @DisplayName("InMemoryRatingClaimStore")
    class RatingClaim {

        @Test
        @DisplayName("every expired claim is invisible after ONE operation")
        void expiryIsExactBeyondOneSweepBudget() {
            var clock = clockAt(T0);
            var store = new InMemoryRatingClaimStore(clock, TTL, InMemoryRatingClaimStore.DEFAULT_MAX_ENTRIES);

            for (int i = 0; i < BEYOND_ONE_SWEEP_BUDGET; i++) {
                store.record(TENANT, "w-" + i, charged("10.00", T0));
            }

            clock.advance(TTL.plusSeconds(1));

            for (int i = 0; i < BEYOND_ONE_SWEEP_BUDGET; i++) {
                assertThat(store.find(TENANT, "w-" + i)).as("w-%d", i).isEmpty();
            }
        }

        @Test
        @DisplayName("an expired claim compares as absent, so a lost drawdown is re-chargeable")
        void expiredClaimComparesAsAbsent() {
            var clock = clockAt(T0);
            var store = new InMemoryRatingClaimStore(clock, TTL, 64);
            store.record(TENANT, "w", charged("10.00", T0));

            clock.advance(TTL.plusSeconds(1));

            assertThat(store.compareAndSet(TENANT, "w", Optional.empty(), charged("25.00", clock.instant())))
                .isTrue();
            assertThat(store.find(TENANT, "w")).isPresent();
            assertThat(store.find(TENANT, "w").orElseThrow().amount()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("a live claim still rejects a stale compare-and-set")
        void liveClaimRejectsStaleCompareAndSet() {
            var clock = clockAt(T0);
            var store = new InMemoryRatingClaimStore(clock, TTL, 64);
            store.record(TENANT, "w", charged("10.00", T0));

            assertThat(store.compareAndSet(TENANT, "w", Optional.empty(), charged("99.00", T0))).isFalse();
            assertThat(store.compareAndSet(TENANT, "w", Optional.of(charged("11.00", T0)), charged("99.00", T0)))
                .isFalse();
            assertThat(store.find(TENANT, "w")).isPresent();
            assertThat(store.find(TENANT, "w").orElseThrow().amount()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("remove is fenced on the expected value")
        void removeIsFenced() {
            var clock = clockAt(T0);
            var store = new InMemoryRatingClaimStore(clock, TTL, 64);
            store.record(TENANT, "w", charged("10.00", T0));

            assertThat(store.remove(TENANT, "w", charged("11.00", T0))).isFalse();
            assertThat(store.remove(TENANT, "w", charged("10.00", T0))).isTrue();
            assertThat(store.find(TENANT, "w")).isEmpty();
        }

        @Test
        @DisplayName("the entry cap is still enforced")
        void entryCapIsEnforced() {
            var store = new InMemoryRatingClaimStore(clockAt(T0), TTL, 128);
            for (int i = 0; i < 10_000; i++) {
                store.record(TENANT, "w-" + i, charged("1.00", T0));
            }
            assertThat(store.find(TENANT, "w-0")).isEmpty();
            assertThat(store.find(TENANT, "w-9999")).isPresent();
        }
    }

    private static Charged charged(String amount, Instant at) {
        return new Charged(new BigDecimal(amount), "USD", at);
    }

    /** Test clock advanced explicitly, so nothing in these tests depends on wall-clock time. */
    static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
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
            return instant;
        }
    }
}