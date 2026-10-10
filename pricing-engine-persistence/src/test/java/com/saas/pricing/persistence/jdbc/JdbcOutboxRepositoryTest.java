package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxEvent;
import com.saas.pricing.core.model.event.TraceContext;
import com.saas.pricing.persistence.jdbc.JdbcOutboxRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Outbox persistence.
 *
 * <p>Note on coverage: migration V13's PostgreSQL rules are not exercised here because the H2 build
 * used by this suite cannot execute plpgsql.
 */
class JdbcOutboxRepositoryTest extends BaseJdbcRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-08T00:01:00Z");

    private JdbcOutboxRepository repository() {
        return new JdbcOutboxRepository(jdbcTemplate);
    }

    private static OutboxEvent event(String id) {
        return OutboxEvent.queued(id, "invoice.finalized", "t1", "INVOICE",
            "inv-1", "{\"invoiceId\":\"inv-1\"}", T0, T0);
    }

    @Test
    @DisplayName("an event round-trips")
    void roundTrips() {
        var repo = repository();
        repo.enqueue(event("e1"));

        var loaded = repo.findByTenant(TenantId.of("t1"), 10);

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().topic()).isEqualTo("invoice.finalized");
        assertThat(loaded.getFirst().aggregateId()).isEqualTo("inv-1");
        assertThat(loaded.getFirst().attempts()).isZero();
        assertThat(loaded.getFirst().isDelivered()).isFalse();
    }

    @Test
    @DisplayName("an identical re-enqueue is a no-op, a conflicting one is refused")
    void enqueueSemantics() {
        var repo = repository();
        repo.enqueue(event("e1"));
        repo.enqueue(event("e1"));

        assertThat(repo.findByTenant(TenantId.of("t1"), 10))
            .as("a retried transaction writing the same event must not error")
            .hasSize(1);

        var conflicting = OutboxEvent.queued("e1", "invoice.finalized", "t1", "INVOICE",
            "inv-1", "{\"different\":true}", T0, T0);
        assertThatThrownBy(() -> repo.enqueue(conflicting))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("different content");
    }

    @Test
    @DisplayName("delivery bookkeeping updates without touching the event")
    void recordDeliveryUpdatesBookkeeping() {
        var repo = repository();
        repo.enqueue(event("e1"));

        repo.recordDelivery(event("e1").failed("connection refused", T1));

        var loaded = repo.findByTenant(TenantId.of("t1"), 10).getFirst();
        assertThat(loaded.attempts()).isEqualTo(1);
        assertThat(loaded.lastError()).contains("connection refused");
        assertThat(loaded.nextAttemptAt()).contains(T1);
        assertThat(loaded.payload())
            .as("the payload itself must be unchanged by a delivery failure")
            .isEqualTo("{\"invoiceId\":\"inv-1\"}");
    }

    @Test
    @DisplayName("a delivered event leaves the due queue but stays in history")
    void deliveredLeavesDueQueue() {
        var repo = repository();
        repo.enqueue(event("e1"));
        assertThat(repo.findDue(T0, 10)).hasSize(1);

        repo.recordDelivery(event("e1").delivered(T1));

        assertThat(repo.findDue(T1.plusSeconds(3600), 10)).isEmpty();
        assertThat(repo.findByTenant(TenantId.of("t1"), 10))
            .as("an operator must still see that it went out")
            .hasSize(1);
    }

    @Test
    @DisplayName("the due query honours its bound and excludes exhausted events")
    void dueRespectsLimitAndExhaustion() {
        var repo = repository();
        for (int i = 0; i < 5; i++) {
            repo.enqueue(event("e-" + i));
        }

        assertThat(repo.findDue(T0, 3)).hasSize(3);
        assertThat(repo.findDue(T0, 100)).hasSize(5);

        OutboxEvent exhausted = event("e-exhausted");
        for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
            exhausted = exhausted.failed("still failing", T0.plusSeconds(60));
        }
        repo.recordDelivery(exhausted);

        assertThat(repo.findDue(T0.plusSeconds(3600), 100))
            .as("an event that exhausted its budget must stop being retried")
            .extracting(OutboxEvent::eventId)
            .doesNotContain("e-exhausted");
        assertThat(repo.findUndelivered("invoice.finalized", 100))
            .extracting(OutboxEvent::eventId)
            .contains("e-exhausted");
    }

    // -------------------------------------------------------------------------------------------
    // Trace context: the point of storing it is that a trace survives the asynchronous hop, so the
    // assertions are about what a relay would actually find on the row.
    // -------------------------------------------------------------------------------------------

    private static final String TRACEPARENT = com.saas.pricing.core.model.event.TraceContext.EXAMPLE_TRACEPARENT;

    @Test
    @DisplayName("the trace an event was queued under survives to the database")
    void traceContextRoundTrips() {
        var repo = new JdbcOutboxRepository(jdbcTemplate, () -> TraceContext.of(TRACEPARENT));
        repo.enqueue(event("e1"));

        var loaded = repo.findDue(T0, 10).getFirst();

        assertThat(loaded.traceContext().traceparent())
            .as("a relay reading this row has to be able to stitch the consumer's span to the "
                + "request that changed the billing state")
            .contains(TRACEPARENT);
    }

    @Test
    @DisplayName("tracestate is persisted alongside the traceparent")
    void tracestateRoundTrips() {
        var repo = new JdbcOutboxRepository(jdbcTemplate, () -> new TraceContext(
            java.util.Optional.of(TRACEPARENT), java.util.Optional.of("vendor=abc,other=def")));
        repo.enqueue(event("e1"));

        var loaded = repo.findDue(T0, 10).getFirst();

        assertThat(loaded.traceContext().tracestate()).contains("vendor=abc,other=def");
        assertThat(loaded.traceContext().asCarrierHeaders())
            .containsEntry("traceparent", TRACEPARENT)
            .containsEntry("tracestate", "vendor=abc,other=def");
    }

    @Test
    @DisplayName("rows written before V22 have no trace and still load")
    void missingColumnsMeanNoTrace() {
        var repo = repository();
        repo.enqueue(event("e1"));

        assertThat(repo.findDue(T0, 10).getFirst().traceContext().isPresent())
            .as("the columns are nullable so pre-existing rows stay valid")
            .isFalse();
    }

    /**
     * The columns are ordinary text a DBA can edit. A value that no longer parses must not stop the
     * billing event from being delivered — losing the trace link is recoverable, refusing to
     * publish the invoice is not.
     */
    @Test
    @DisplayName("a hand-edited traceparent is dropped rather than refused")
    void malformedStoredTraceparentIsDroppedNotFatal() {
        var repo = new JdbcOutboxRepository(jdbcTemplate, () -> TraceContext.of(TRACEPARENT));
        repo.enqueue(event("e1"));
        jdbcTemplate.update("UPDATE outbox_events SET traceparent = ? WHERE event_id = ?",
            "not-a-traceparent", "e1");

        var loaded = repo.findDue(T0, 10);

        assertThat(loaded).as("the event must still be deliverable").hasSize(1);
        assertThat(loaded.getFirst().traceContext().isPresent()).isFalse();
    }

    @Test
    @DisplayName("a retry on an untraced thread is a duplicate, not a conflict")
    void traceContextIsNotPartOfTheConflictRule() {
        var traced = new JdbcOutboxRepository(jdbcTemplate, () -> TraceContext.of(TRACEPARENT));
        traced.enqueue(event("e1"));

        // Same transaction retried from a thread with no trace active.
        new JdbcOutboxRepository(jdbcTemplate).enqueue(event("e1"));

        assertThat(traced.findByTenant(TenantId.of("t1"), 10))
            .as("the conflict check compares payload and topic only, exactly as it did before "
                + "trace context existed")
            .hasSize(1);
        assertThat(traced.findDue(T0, 10).getFirst().traceContext().traceparent())
            .as("the original cause is kept, not downgraded by the retry")
            .contains(TRACEPARENT);
    }
}