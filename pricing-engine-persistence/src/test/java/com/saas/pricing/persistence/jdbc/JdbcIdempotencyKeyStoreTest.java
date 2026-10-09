package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.idempotency.IdempotencyDecision;
import com.saas.pricing.core.model.idempotency.IdempotencyRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDBC implementation of the IETF {@code Idempotency-Key} contract.
 *
 * <p>These exist because the in-memory store and the JDBC store have entirely different atomicity
 * mechanisms - {@code compute} versus a primary-key collision - and passing in-memory tests say
 * nothing about whether the SQL is correct. In particular the reclaim path cannot be exercised
 * against a {@code Map} at all, because only a real conditional UPDATE has an update count.
 */
class JdbcIdempotencyKeyStoreTest extends BaseJdbcRepositoryTest {

    private static final TenantId TENANT = TenantId.of("t1");
    private static final TenantId OTHER_TENANT = TenantId.of("t2");
    private static final String KEY = "order-42";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private JdbcIdempotencyKeyStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcIdempotencyKeyStore(jdbcTemplate);
    }

    private static String fingerprint(String body) {
        return IdempotencyRecord.fingerprintOf(body);
    }

    @Test
    @DisplayName("a new key proceeds and claims")
    void newKeyProceeds() {
        var decision = store.decide(TENANT, KEY, fingerprint("{}"), TTL, NOW);

        assertThat(decision.action()).isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    @Test
    @DisplayName("an identical retry replays the stored response body and status")
    void identicalRetryReplays() {
        String fp = fingerprint("{\"invoiceId\":\"inv-1\"}");
        var claim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        store.complete(TENANT, claim, 201, "{\"invoiceId\":\"inv-1\"}");

        var retry = store.decide(TENANT, KEY, fp, TTL, NOW.plusSeconds(5));

        assertThat(retry.action()).isEqualTo(IdempotencyDecision.Action.REPLAY);
        assertThat(retry.httpStatus()).isEqualTo(201);
        assertThat(retry.stored().responseBody()).contains("inv-1");
    }

    @Test
    @DisplayName("the same key with a different payload is a 422 conflict")
    void keyReuseWithDifferentPayloadConflicts() {
        store.decide(TENANT, KEY, fingerprint("{\"amount\":\"10.00\"}"), TTL, NOW);

        var conflict = store.decide(TENANT, KEY, fingerprint("{\"amount\":\"9999.00\"}"),
            TTL, NOW.plusSeconds(1));

        assertThat(conflict.action()).isEqualTo(IdempotencyDecision.Action.CONFLICT);
        assertThat(conflict.httpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("a duplicate while the original runs gets 409")
    void inFlightDuplicateConflicts() {
        String fp = fingerprint("{}");
        store.decide(TENANT, KEY, fp, TTL, NOW);

        var duplicate = store.decide(TENANT, KEY, fp, TTL, NOW.plusSeconds(1));

        assertThat(duplicate.action()).isEqualTo(IdempotencyDecision.Action.IN_FLIGHT);
        assertThat(duplicate.httpStatus()).isEqualTo(409);
    }

    /**
     * The reason the key is tenant-scoped in the schema.
     *
     * <p>Without {@code tenant_id} in the primary key, {@code t2} reusing a key {@code t1} already
     * completed is served {@code t1}'s stored response body - a cross-tenant data leak that a
     * single-tenant test can never see.
     */
    @Test
    @DisplayName("the same key in another tenant is an independent request")
    void keysAreScopedByTenant() {
        String fp = fingerprint("{\"customerId\":\"c1\"}");
        var claim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        store.complete(TENANT, claim, 201, "{\"invoiceId\":\"t1-secret-invoice\"}");

        var otherTenant = store.decide(OTHER_TENANT, KEY, fp, TTL, NOW.plusSeconds(1));

        assertThat(otherTenant.action())
            .as("t2 must not be served t1's invoice")
            .isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    @Test
    @DisplayName("releasing a claim lets the caller retry")
    void releaseAllowsRetry() {
        String fp = fingerprint("{}");
        var claim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();

        store.release(TENANT, claim);

        assertThat(store.decide(TENANT, KEY, fp, TTL, NOW.plusSeconds(1)).action())
            .isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    @Test
    @DisplayName("an expired claim is reclaimable, so a wedged key cannot outlive its TTL")
    void expiredClaimIsReclaimed() {
        String fp = fingerprint("{}");
        store.decide(TENANT, KEY, fp, TTL, NOW);

        var later = store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(1));

        assertThat(later.action()).isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    /**
     * A slow original that outlives its TTL must not clobber the newer execution's result.
     *
     * <p>Both {@code complete} and {@code release} are fenced on {@code recorded_at}. Without that,
     * {@code t1} reclaiming at T0+TTL and completing late would overwrite whatever {@code t2}
     * stored, and every later retry would replay the wrong invoice.
     */
    @Test
    @DisplayName("a late finisher cannot overwrite or delete a newer execution")
    void lateWritesAreFenced() {
        String fp = fingerprint("{}");
        var slowClaim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        var newClaim = store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(1)).claim();
        store.complete(TENANT, newClaim, 201, "{\"invoiceId\":\"inv-new\"}");

        // The stale original now finishes and tries to clean up after itself.
        store.complete(TENANT, slowClaim, 201, "{\"invoiceId\":\"inv-stale\"}");
        store.release(TENANT, slowClaim);

        var replay = store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(2));
        assertThat(replay.action()).isEqualTo(IdempotencyDecision.Action.REPLAY);
        assertThat(replay.stored().responseBody())
            .as("the newer execution owns the key once it has reclaimed it")
            .contains("inv-new");
    }

    /**
     * Exactly one of many concurrent claims may proceed.
     *
     * <p>This is the property the whole table exists for. The claim is a single INSERT, so the
     * primary key - not application logic - is what makes the losers fail.
     */
    @Test
    @DisplayName("exactly one of many concurrent requests executes")
    void exactlyOneOfManyConcurrentRequestsExecutes() throws Exception {
        String fp = fingerprint("{\"invoiceId\":\"inv-1\"}");
        int threads = 16;

        var executions = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (store.decide(TENANT, KEY, fp, TTL, NOW).shouldExecute()) {
                    executions.incrementAndGet();
                }
                return 0;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(executions.get())
            .as("a check-then-insert claim would let every racing thread through")
            .isEqualTo(1);
    }

    /**
     * The reclaim race, which only a real conditional UPDATE can lose.
     *
     * <p>Every thread finds the row expired and attempts to reclaim it. The UPDATE is guarded by
     * {@code expires_at <= ?}, so only one matches. Returning {@code PROCEED} without inspecting
     * the update count - the obvious way to write this - lets the losers proceed anyway and
     * re-creates the double-issue the table exists to prevent.
     */
    @Test
    @DisplayName("exactly one of many concurrent requests reclaims an expired claim")
    void exactlyOneReclaimsExpiredClaim() throws Exception {
        String fp = fingerprint("{}");
        store.decide(TENANT, KEY, fp, TTL, NOW);
        Instant afterExpiry = NOW.plus(TTL).plusSeconds(1);

        int threads = 16;
        var executions = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (store.decide(TENANT, KEY, fp, TTL, afterExpiry).shouldExecute()) {
                    executions.incrementAndGet();
                }
                return 0;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(executions.get())
            .as("ignoring the reclaim UPDATE count lets two racers both execute one expired key")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a nanosecond claim time still completes and replays after the round trip")
    void nanosecondClaimTimeSurvivesTheRoundTrip() {
        // Regression: complete() fences on recorded_at equality. Instant.now() carries nanoseconds
        // while TIMESTAMP WITH TIME ZONE stores microseconds, so the stored row could never equal
        // the in-memory claim and the key stayed IN_FLIGHT until its TTL, 409-ing every retry.
        Instant withNanos = Instant.parse("2026-10-08T12:00:00.123456789Z");
        String fp = fingerprint("{}");
        var claim = store.decide(TENANT, KEY, fp, TTL, withNanos).claim();

        store.complete(TENANT, claim, 201, "{\"invoiceId\":\"inv-nano\"}");

        var retry = store.decide(TENANT, KEY, fp, TTL, withNanos.plusSeconds(1));
        assertThat(retry.action()).isEqualTo(IdempotencyDecision.Action.REPLAY);
        assertThat(retry.stored().responseBody()).contains("inv-nano");
    }
}