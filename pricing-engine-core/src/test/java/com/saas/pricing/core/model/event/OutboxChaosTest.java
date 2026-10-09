package com.saas.pricing.core.model.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the outbox does when delivery goes wrong.
 *
 * <p>The outbox guarantees a committed event cannot be lost from the database. It does not
 * guarantee delivery — the host runs the dispatcher. That makes the failure surface unusually
 * rich: duplicates, reordering, partial failure, and permanent failure are all normal, and the
 * record has to stay trustworthy through every one of them.
 *
 * <p>These tests inject the disorder directly rather than waiting for a broker to produce it.
 */
class OutboxChaosTest {

    private static final Instant T0 = Instant.parse("2026-10-09T10:00:00Z");

    private final OutboxRepository outbox = new InMemoryOutboxRepository();

    private static OutboxEvent event(String id) {
        return OutboxEvent.queued(id, "invoice.finalized", "t1", "Invoice", "inv-" + id,
            "{\"amount\":\"100.00\"}", T0, T0);
    }

    @Test
    @DisplayName("a redelivered event with identical content is a silent no-op, not a second event")
    void duplicateDeliveryIsIdempotent() {
        outbox.enqueue(event("e1"));
        for (int i = 0; i < 50; i++) {
            outbox.enqueue(event("e1"));
        }
        assertThat(outbox.findUndelivered(null, 100))
            .as("50 redeliveries must not become 50 events")
            .hasSize(1);
    }

    @Test
    @DisplayName("the same id carrying different content is refused, not overwritten")
    void conflictingContentIsRefused() {
        outbox.enqueue(event("e1"));
        OutboxEvent tampered = OutboxEvent.queued(
            "e1", "invoice.finalized", "t1", "Invoice", "inv-e1",
            "{\"amount\":\"999.00\"}", T0, T0);

        assertThatThrownBy(() -> outbox.enqueue(tampered))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already queued with different content");

        assertThat(outbox.findUndelivered(null, 100))
            .as("the original must survive the tampering attempt")
            .hasSize(1);
        assertThat(outbox.findUndelivered(null, 100).getFirst().payload())
            .contains("100.00");
    }

    @Test
    @DisplayName("a delayed success arriving after the event was declared exhausted still records")
    void lateSuccessAfterExhaustion() {
        outbox.enqueue(event("e1"));
        OutboxEvent current = event("e1");
        for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
            current = current.failed("connection refused", T0.plusSeconds(60));
        }
        outbox.recordDelivery(current);
        assertThat(current.isExhausted()).isTrue();
        assertThat(outbox.findUndelivered(null, 10)).as("exhausted: still owed to a human").hasSize(1);

        // The subscriber was merely slow. Its success arrives after the budget was spent.
        outbox.recordDelivery(current.delivered(T0.plusSeconds(3600)));

        assertThat(outbox.findUndelivered(null, 10))
            .as("a late success must clear the debt, not be discarded")
            .isEmpty();
        assertThat(outbox.findDue(T0.plusSeconds(999999), 10))
            .as("a delivered event is never due again")
            .isEmpty();
    }

    @Test
    @DisplayName("failure schedules a backoff, and delivery is not attempted before it is due")
    void backoffIsHonoured() {
        outbox.enqueue(event("e1"));
        OutboxEvent failed = event("e1").failed("timeout", T0.plusSeconds(300));
        outbox.recordDelivery(failed);

        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.lastError()).contains("timeout");
        assertThat(outbox.findDue(T0.plusSeconds(299), 10))
            .as("retrying early would hammer a struggling subscriber")
            .isEmpty();
        assertThat(outbox.findDue(T0.plusSeconds(300), 10)).hasSize(1);
    }

    @Test
    @DisplayName("an event never stays due once its retry budget is spent")
    void exhaustedEventIsNotRetriedForever() {
        outbox.enqueue(event("e1"));
        OutboxEvent current = event("e1");
        for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
            current = current.failed("still down", T0.plusSeconds(60));
        }
        outbox.recordDelivery(current);

        assertThat(outbox.findDue(T0.plusSeconds(86400), 10))
            .as("an exhausted event must stop consuming delivery budget")
            .isEmpty();
        assertThat(outbox.findUndelivered(null, 10)).hasSize(1);
    }

    @Test
    @DisplayName("concurrent enqueue of the same event yields exactly one record")
    void concurrentEnqueueYieldsOneRecord() throws Exception {
        int threads = 64;
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        var rejected = new AtomicInteger();
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        latch.await();
                        outbox.enqueue(event("race"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        rejected.incrementAndGet();
                    }
                }));
            }
            latch.countDown();
            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outbox.findUndelivered(null, 100))
            .as("64 threads announcing the same change must produce one event")
            .hasSize(1);
        assertThat(rejected.get())
            .as("identical concurrent content is a no-op, not a rejection")
            .isZero();
    }

    @Test
    @DisplayName("findDue respects its limit and never returns more than asked")
    void findDueRespectsLimit() {
        for (int i = 0; i < 25; i++) {
            outbox.enqueue(event("e" + i));
        }
        assertThat(outbox.findDue(T0, 10)).hasSize(10);
        assertThat(outbox.findDue(T0, 100)).hasSize(25);
    }
}