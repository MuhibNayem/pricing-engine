package com.saas.pricing.core.model.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace an outbox event was queued under must still be there when it is delivered.
 *
 * <p>Every mature outbox does this — a {@code traceparent} column lifted into the broker header by
 * the relay. What the tests here pin is not the mechanism but the two properties that make it
 * useful: the context is captured <em>once</em>, at the transaction that caused the state change,
 * and it survives every later copy of the row.</p>
 */
@DisplayName("Outbox events carry the trace they were queued under")
class OutboxTraceContextTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TRACEPARENT = TraceContext.EXAMPLE_TRACEPARENT;

    private static OutboxEvent event(String eventId) {
        return OutboxEvent.queued(eventId, "invoice.finalised", "acme", "invoice",
            "inv-1", "{\"id\":\"inv-1\"}", T0, T0);
    }

    @Test
    @DisplayName("enqueue stamps the trace active at that moment")
    void enqueueStampsTheCurrentTrace() {
        OutboxRepository outbox =
            new InMemoryOutboxRepository(() -> TraceContext.of(TRACEPARENT));

        outbox.enqueue(event("e1"));

        List<OutboxEvent> stored = outbox.findUndelivered("invoice.finalised", 10);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().traceContext().traceparent()).contains(TRACEPARENT);
    }

    @Test
    @DisplayName("no provider means no trace, which is the pre-existing behaviour")
    void withoutAProviderEventsCarryNothing() {
        OutboxRepository outbox = new InMemoryOutboxRepository();

        outbox.enqueue(event("e1"));

        assertThat(outbox.findUndelivered(null, 10).getFirst().traceContext().isPresent()).isFalse();
    }

    @Test
    @DisplayName("the context survives delivery, retry and signing")
    void contextSurvivesEveryCopyOfTheRow() {
        OutboxRepository outbox =
            new InMemoryOutboxRepository(() -> TraceContext.of(TRACEPARENT));
        outbox.enqueue(event("e1"));

        OutboxEvent signed = outbox.findUndelivered(null, 10).getFirst()
            .signed("hmac-value")
            .failed("broker unavailable", T0.plusSeconds(30))
            .delivered(T0.plusSeconds(60));
        outbox.recordDelivery(signed);

        assertThat(outbox.findByTenant(new com.saas.pricing.core.model.TenantId("acme"), 10)
            .getFirst().traceContext().traceparent())
            .as("every copy of the row has to keep the trace, or the link is lost the first time "
                + "delivery retries")
            .contains(TRACEPARENT);
    }

    /**
     * The asymmetry that makes this correct rather than merely present.
     *
     * <p>Stamping is for the enqueue only. Re-reading the provider on a delivery pass would
     * overwrite the link to the request that changed the state with a link to the dispatcher's own
     * poll — and every consumer's event would appear to descend from whichever scheduled sweep
     * happened to pick it up, which is not an association anyone debugging a billing question would
     * ask for, let alone trust.</p>
     */
    @Test
    @DisplayName("delivery does not re-stamp, so the dispatcher's trace cannot overwrite the cause")
    void deliveryNeverReStampsTheTrace() {
        OutboxRepository outbox =
            new InMemoryOutboxRepository(() -> TraceContext.of(TRACEPARENT));
        outbox.enqueue(event("e1"));

        // The dispatcher is now running under a different trace entirely.
        OutboxEvent dispatcherView = outbox.findDue(T0, 10).getFirst()
            .withTraceContext(TraceContext.of(
                "00-11111111111111111111111111111111-2222222222222222-01"));

        assertThat(dispatcherView.traceContext().traceparent())
            .as("withTraceContext replaces deliberately; the repositories just never call it on a "
                + "delivery pass")
            .contains("00-11111111111111111111111111111111-2222222222222222-01");

        outbox.recordDelivery(outbox.findDue(T0, 10).getFirst().delivered(T0.plusSeconds(1)));

        assertThat(outbox.findByTenant(new com.saas.pricing.core.model.TenantId("acme"), 10)
            .getFirst().traceContext().traceparent())
            .as("the original cause survives an ordinary delivery recorded by the repository")
            .contains(TRACEPARENT);
    }

    @Test
    @DisplayName("tracestate rides along, and a re-enqueue with no trace does not erase it")
    void tracestateRidesAlong() {
        TraceContextProvider provider = () -> new TraceContext(
            Optional.of(TRACEPARENT), Optional.of("vendor=abc"));
        OutboxRepository outbox = new InMemoryOutboxRepository(provider);
        outbox.enqueue(event("e1"));

        assertThat(outbox.findUndelivered(null, 10).getFirst().traceContext().tracestate())
            .contains("vendor=abc");

        // A retried transaction writes the same event again, this time off a background thread with
        // no trace. The duplicate must be a no-op rather than a downgrade.
        outbox.enqueue(event("e1"));

        assertThat(outbox.findUndelivered(null, 10).getFirst().traceContext().tracestate())
            .as("an identical re-enqueue with no trace must not blank a stored context")
            .contains("vendor=abc");
    }

    @Test
    @DisplayName("withTraceContext replaces unconditionally, including with none")
    void withTraceContextAlwaysReplaces() {
        OutboxEvent stamped = event("e1").withTraceContext(TraceContext.of(TRACEPARENT));

        assertThat(stamped.traceContext().isPresent()).isTrue();
        assertThat(stamped.withTraceContext(TraceContext.NONE).traceContext().isPresent()).isFalse();
    }

    @Test
    @DisplayName("trace context is not content: a differing trace is still the same event")
    void traceContextIsExcludedFromTheDuplicateRule() {
        OutboxEvent fromRequest = event("e1").withTraceContext(TraceContext.of(TRACEPARENT));
        OutboxEvent fromRetry = event("e1");

        assertThat(fromRequest.hasSameContentAs(fromRetry))
            .as("a transaction retried on a thread with no trace is a duplicate, not a conflict")
            .isTrue();

        assertThat(fromRequest.hasSameContentAs(
            event("e1").withTraceContext(TraceContext.of(
                "00-11111111111111111111111111111111-2222222222222222-01"))))
            .isTrue();

        assertThat(fromRequest.hasSameContentAs(
            OutboxEvent.queued("e1", "invoice.finalised", "acme", "invoice", "inv-1",
                "{\"id\":\"inv-2\"}", T0, T0)))
            .as("different payload is still a conflict")
            .isFalse();
    }

    @Test
    @DisplayName("a retried transaction on an untraced thread does not fail or downgrade the row")
    void retryWithoutATraceIsANoOp() {
        // One repository, and the provider changes under it: the first attempt ran inside a
        // request, the retry ran on a worker thread. Going through the repository rather than
        // calling hasSameContentAs directly is the point - the duplicate rule is applied there.
        TraceContextProvider tracedThenNot = new TraceContextProvider() {
            private boolean firstCall = true;

            @Override
            public TraceContext current() {
                TraceContext result = firstCall ? TraceContext.of(TRACEPARENT) : TraceContext.NONE;
                firstCall = false;
                return result;
            }
        };
        OutboxRepository outbox = new InMemoryOutboxRepository(tracedThenNot);

        outbox.enqueue(event("e1"));
        outbox.enqueue(event("e1"));

        assertThat(outbox.findUndelivered(null, 10)).hasSize(1);
        assertThat(outbox.findUndelivered(null, 10).getFirst().traceContext().traceparent())
            .as("comparing the trace as content would refuse the retry inside the caller's "
                + "transaction, and would downgrade a committed row when it did not")
            .contains(TRACEPARENT);
    }
}