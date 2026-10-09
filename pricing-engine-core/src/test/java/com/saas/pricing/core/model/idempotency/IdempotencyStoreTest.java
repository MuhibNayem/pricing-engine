package com.saas.pricing.core.model.idempotency;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.spi.IdempotencyKeyStore;
import com.saas.pricing.core.spi.impl.InMemoryIdempotencyKeyStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP idempotency, per the IETF {@code Idempotency-Key} header semantics.
 *
 * <p>The rule the draft states most forcefully is that duplicate records "involving any kind of
 * money transfer MUST NOT be allowed", so the tests that matter most are the concurrent one (two
 * requests arriving together must produce exactly one execution) and the reclaim one (an expired
 * key must be reusable, without opening a window in which both racers proceed).
 */
class IdempotencyStoreTest {

    private static final String KEY = "8f1e2b7c-order-42";
    private static final Duration TTL = Duration.ofHours(24);
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final TenantId TENANT = TenantId.of("t1");
    private static final TenantId OTHER_TENANT = TenantId.of("t2");

    private static String fingerprint(String body) {
        return IdempotencyRecord.fingerprintOf(body);
    }

    @Test
    @DisplayName("a new key proceeds and claims")
    void newKeyProceeds() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();

        var decision = store.decide(TENANT, KEY, fingerprint("{}"), TTL, NOW);

        assertThat(decision.action()).isEqualTo(IdempotencyDecision.Action.PROCEED);
        assertThat(decision.shouldExecute()).isTrue();
        assertThat(decision.claim().status()).isEqualTo(IdempotencyRecord.Status.IN_FLIGHT);
    }

    @Test
    @DisplayName("an identical retry replays the original response verbatim")
    void identicalRetryReplays() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{\"invoiceId\":\"inv-1\"}");

        var claim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        store.complete(TENANT, claim, 201, "{\"invoiceId\":\"inv-1\"}");

        var retry = store.decide(TENANT, KEY, fp, TTL, NOW.plusSeconds(5));

        assertThat(retry.action()).isEqualTo(IdempotencyDecision.Action.REPLAY);
        assertThat(retry.httpStatus())
            .as("the client asked for this request; the answer has not changed")
            .isEqualTo(201);
        assertThat(retry.stored().responseBody())
            .as("the client retried to learn WHICH invoice was created")
            .contains("inv-1");
    }

    @Test
    @DisplayName("an original error is replayed as the same error, not retried as a success")
    void originalErrorIsReplayed() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{\"bad\":true}");

        var claim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        store.complete(TENANT, claim, 422, "{\"error\":\"invalid currency\"}");

        var retry = store.decide(TENANT, KEY, fp, TTL, NOW.plusSeconds(1));

        assertThat(retry.action()).isEqualTo(IdempotencyDecision.Action.REPLAY);
        assertThat(retry.httpStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("the same key with a different payload is a 422 conflict")
    void keyReuseWithDifferentPayloadConflicts() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        store.decide(TENANT, KEY, fingerprint("{\"amount\":\"10.00\"}"), TTL, NOW);

        var conflict = store.decide(TENANT, KEY, fingerprint("{\"amount\":\"9999.00\"}"),
            TTL, NOW.plusSeconds(1));

        assertThat(conflict.action()).isEqualTo(IdempotencyDecision.Action.CONFLICT);
        assertThat(conflict.httpStatus())
            .as("replaying here would tell the caller it created something it did not")
            .isEqualTo(422);
        assertThat(conflict.shouldExecute()).isFalse();
    }

    @Test
    @DisplayName("a duplicate while the original runs gets 409, never a partial result")
    void inFlightDuplicateConflicts() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{}");

        store.decide(TENANT, KEY, fp, TTL, NOW);
        var duplicate = store.decide(TENANT, KEY, fp, TTL, NOW.plusSeconds(1));

        assertThat(duplicate.action()).isEqualTo(IdempotencyDecision.Action.IN_FLIGHT);
        assertThat(duplicate.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("releasing a claim lets the caller retry")
    void releaseAllowsRetry() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{}");

        var claim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        store.release(TENANT, claim);

        assertThat(store.decide(TENANT, KEY, fp, TTL, NOW.plusSeconds(1)).action())
            .isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    /**
     * One tenant's key must never serve another tenant's response.
     *
     * <p>Idempotency keys are chosen by clients and plausible ones collide - a date-based key, a
     * timestamped UUID, a key minted once and reused by a retrying proxy. Without tenant scoping,
     * {@code t2} reusing a key {@code t1} already used is handed {@code t1}'s stored response body.
     */
    @Test
    @DisplayName("the same key in another tenant is an independent request, not a replay")
    void keysAreScopedByTenant() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{\"customerId\":\"c1\"}");

        var claim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        store.complete(TENANT, claim, 201, "{\"invoiceId\":\"t1-secret-invoice\"}");

        var otherTenant = store.decide(OTHER_TENANT, KEY, fp, TTL, NOW.plusSeconds(1));

        assertThat(otherTenant.action())
            .as("t2 must not be served t1's invoice; that is a cross-tenant data leak")
            .isEqualTo(IdempotencyDecision.Action.PROCEED);
        assertThat(otherTenant.shouldExecute()).isTrue();
    }

    @Test
    @DisplayName("an expired claim does not wedge the key forever")
    void expiredClaimIsReclaimed() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{}");

        store.decide(TENANT, KEY, fp, TTL, NOW);
        // The original request never completed and its claim aged out.
        var later = store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(1));

        assertThat(later.action())
            .as("a wedged key would make the endpoint unusable for this client forever")
            .isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    /**
     * A slow original that outlives its TTL must not clobber the newer execution's result.
     *
     * <p>{@code t1} claims at T0 and hangs. At T0+TTL the key is reclaimed and {@code t2} completes
     * it. When {@code t1} finally returns, completing on the key alone would overwrite {@code t2}'s
     * response with {@code t1}'s - so a third retry replays the wrong invoice.
     */
    @Test
    @DisplayName("a late finisher cannot overwrite the result of a newer execution")
    void lateCompletionIsFenced() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{}");

        var slowClaim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        var newClaim = store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(1)).claim();
        store.complete(TENANT, newClaim, 201, "{\"invoiceId\":\"inv-new\"}");

        store.complete(TENANT, slowClaim, 201, "{\"invoiceId\":\"inv-stale\"}");

        var replay = store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(2));
        assertThat(replay.stored().responseBody())
            .as("the newer execution owns the key once it has reclaimed it")
            .contains("inv-new");
    }

    /** The mirror of the fence above: a late release must not delete a live claim. */
    @Test
    @DisplayName("a late release cannot delete a newer execution's claim")
    void lateReleaseIsFenced() {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{}");

        var slowClaim = store.decide(TENANT, KEY, fp, TTL, NOW).claim();
        store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(1));

        store.release(TENANT, slowClaim);

        assertThat(store.decide(TENANT, KEY, fp, TTL, NOW.plus(TTL).plusSeconds(2)).action())
            .as("deleting the live claim would let a third request execute the same work")
            .isEqualTo(IdempotencyDecision.Action.IN_FLIGHT);
    }

    @Test
    @DisplayName("exactly one of many concurrent requests executes")
    void exactlyOneOfManyConcurrentRequestsExecutes() throws Exception {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{\"invoiceId\":\"inv-1\"}");
        int threads = 64;

        var executions = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                var decision = store.decide(TENANT, KEY, fp, TTL, NOW);
                if (decision.shouldExecute()) {
                    executions.incrementAndGet();
                }
                return 0;
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (var task : tasks) {
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (var future : futures) {
            future.get();
        }
        pool.shutdown();

        assertThat(executions.get())
            .as("a check-then-insert claim would let every racing thread through and issue %d invoices", threads)
            .isEqualTo(1);
    }

    /**
     * The reclaim path is the one place a store can accidentally re-admit the race it exists to
     * prevent, so it gets its own concurrent test rather than riding on the one above.
     *
     * <p>All threads arrive after the TTL has elapsed, so every one of them finds the entry expired
     * and attempts to reclaim it. Exactly one may win.
     */
    @Test
    @DisplayName("exactly one of many concurrent requests reclaims an expired claim")
    void exactlyOneReclaimsExpiredClaim() throws Exception {
        IdempotencyKeyStore store = new InMemoryIdempotencyKeyStore();
        String fp = fingerprint("{}");
        store.decide(TENANT, KEY, fp, TTL, NOW);

        Instant afterExpiry = NOW.plus(TTL).plusSeconds(1);
        int threads = 32;
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
            .as("a non-atomic reclaim lets two racers both 'reclaim' one expired key and both execute")
            .isEqualTo(1);
    }
}