package com.saas.pricing.persistence.jdbc;

import com.saas.pricing.core.model.TenantId;
import com.saas.pricing.core.model.event.OutboxEvent;
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
}