package com.saas.pricing.redis.conformance;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.idempotency.IdempotencyDecision;
import com.saas.pricing.core.model.idempotency.IdempotencyRecord;
import com.saas.pricing.core.spi.IdempotencyKeyStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behavioural contract every {@link IdempotencyKeyStore} must satisfy, regardless of where it
 * stores things.
 *
 * <p>This exists because there are now three implementations — in-memory, JDBC, and Redis — and the
 * failure mode that matters is not any one of them being broken. It is one of them being subtly
 * <em>different</em>: an adapter that is fast and slightly wrong is worse than one that is slow and
 * obviously wrong, because nobody checks the fast one twice.</p>
 *
 * <p>So the semantics that are easy to get wrong are pinned here, once, and every implementation is
 * made to pass them. Adding a store means adding a subclass here, not writing its own idea of what
 * a claim is.</p>
 */
@DisplayName("IdempotencyKeyStore conformance")
abstract class IdempotencyKeyStoreConformance {

    protected static final TenantId ACME = new TenantId("acme");
    protected static final TenantId GLOBEX = new TenantId("globex");
    protected static final Duration TTL = Duration.ofHours(24);
    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** A fresh store, so no test can inherit another's claim. */
    protected abstract IdempotencyKeyStore store();

    @Test
    @DisplayName("a first sighting claims the key and carries the claim back")
    void firstSightingProceeds() {
        IdempotencyDecision decision = store().decide(ACME, "k", "fp1", TTL, T0);

        assertThat(decision.action()).isEqualTo(IdempotencyDecision.Action.PROCEED);
        assertThat(decision.claim()).isNotNull();
        assertThat(decision.claim().recordedAt()).isEqualTo(T0);
        assertThat(decision.claim().expiresAt()).isEqualTo(T0.plus(TTL));
    }

    @Test
    @DisplayName("a concurrent duplicate sees IN_FLIGHT, never a second PROCEED")
    void concurrentDuplicateSeesInFlight() {
        IdempotencyKeyStore store = store();
        store.decide(ACME, "k", "fp1", TTL, T0);

        IdempotencyDecision second = store.decide(ACME, "k", "fp1", TTL, T0.plusSeconds(1));

        assertThat(second.action())
            .as("two callers must not both be told to execute")
            .isEqualTo(IdempotencyDecision.Action.IN_FLIGHT);
        assertThat(second.httpStatus()).isEqualTo(409);
        assertThat(second.shouldExecute()).isFalse();
    }

    @Test
    @DisplayName("the same key with different content is a CONFLICT, not a replay")
    void differentFingerprintIsConflict() {
        IdempotencyKeyStore store = store();
        store.decide(ACME, "k", "fp1", TTL, T0);

        IdempotencyDecision conflict = store.decide(ACME, "k", "fp2", TTL, T0.plusSeconds(1));

        assertThat(conflict.action()).isEqualTo(IdempotencyDecision.Action.CONFLICT);
        assertThat(conflict.httpStatus()).isEqualTo(422);
        assertThat(conflict.shouldExecute())
            .as("reusing a key for a different request must not silently execute")
            .isFalse();
    }

    @Test
    @DisplayName("a completed claim is replayed verbatim")
    void completedClaimIsReplayed() {
        IdempotencyKeyStore store = store();
        IdempotencyRecord claim = store.decide(ACME, "k", "fp1", TTL, T0).claim();
        store.complete(ACME, claim, 201, "{\"invoice\":\"INV-1\"}");

        IdempotencyDecision replay = store.decide(ACME, "k", "fp1", TTL, T0.plusSeconds(60));

        assertThat(replay.action()).isEqualTo(IdempotencyDecision.Action.REPLAY);
        assertThat(replay.httpStatus()).isEqualTo(201);
        assertThat(replay.stored().responseBody()).isEqualTo("{\"invoice\":\"INV-1\"}");
        assertThat(replay.shouldExecute()).isFalse();
    }

    @Test
    @DisplayName("a stale claim cannot overwrite a newer execution's result")
    void completeIsFencedOnRecordedAt() {
        IdempotencyKeyStore store = store();
        IdempotencyRecord stale = store.decide(ACME, "k", "fp1", TTL, T0).claim();

        // The claim expires, the key is reclaimed, and a second request runs and completes.
        IdempotencyRecord fresh = store.decide(ACME, "k", "fp1", TTL, T0.plus(TTL).plusSeconds(1)).claim();
        store.complete(ACME, fresh, 201, "{\"invoice\":\"INV-2\"}");

        // The original request finally returns. It must not clobber the newer result.
        store.complete(ACME, stale, 500, "{\"error\":\"stale\"}");

        IdempotencyDecision replay = store.decide(ACME, "k", "fp1", TTL, T0.plus(TTL).plusSeconds(2));
        assertThat(replay.action()).isEqualTo(IdempotencyDecision.Action.REPLAY);
        assertThat(replay.httpStatus())
            .as("the newer execution's result must survive the slow original")
            .isEqualTo(201);
        assertThat(replay.stored().responseBody()).isEqualTo("{\"invoice\":\"INV-2\"}");
    }

    @Test
    @DisplayName("a stale claim cannot delete a newer execution's claim")
    void releaseIsFencedOnRecordedAt() {
        IdempotencyKeyStore store = store();
        IdempotencyRecord stale = store.decide(ACME, "k", "fp1", TTL, T0).claim();

        store.decide(ACME, "k", "fp1", TTL, T0.plus(TTL).plusSeconds(1));   // reclaimed

        store.release(ACME, stale);

        IdempotencyDecision after = store.decide(ACME, "k", "fp1", TTL, T0.plus(TTL).plusSeconds(2));
        assertThat(after.action())
            .as("releasing an expired claim must not free the key under its new owner")
            .isEqualTo(IdempotencyDecision.Action.IN_FLIGHT);
    }

    @Test
    @DisplayName("release frees the key so the caller may retry")
    void releaseAllowsRetry() {
        IdempotencyKeyStore store = store();
        IdempotencyRecord claim = store.decide(ACME, "k", "fp1", TTL, T0).claim();
        store.release(ACME, claim);

        IdempotencyDecision retry = store.decide(ACME, "k", "fp1", TTL, T0.plusSeconds(1));
        assertThat(retry.action()).isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    @Test
    @DisplayName("keys are scoped by tenant: a collision must not leak across tenants")
    void keysAreTenantScoped() {
        IdempotencyKeyStore store = store();
        store.decide(ACME, "shared-key", "fp1", TTL, T0);

        IdempotencyDecision otherTenant = store.decide(GLOBEX, "shared-key", "fp1", TTL, T0);

        assertThat(otherTenant.action())
            .as("one tenant's claim must not answer another's request")
            .isEqualTo(IdempotencyDecision.Action.PROCEED);
    }

    @Test
    @DisplayName("an expired key is claimable again")
    void expiredKeyIsReclaimable() {
        IdempotencyKeyStore store = store();
        store.decide(ACME, "k", "fp1", TTL, T0);

        IdempotencyDecision afterExpiry = store.decide(ACME, "k", "fp1", TTL, T0.plus(TTL).plusSeconds(1));

        assertThat(afterExpiry.action()).isEqualTo(IdempotencyDecision.Action.PROCEED);
    }
}