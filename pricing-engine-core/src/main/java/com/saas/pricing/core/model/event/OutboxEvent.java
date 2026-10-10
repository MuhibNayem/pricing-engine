package com.saas.pricing.core.model.event;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An event queued for delivery to an external system.
 *
 * <h2>Why an outbox rather than publishing inline</h2>
 * Publishing straight after a write has an unrecoverable failure mode: the database commits and the
 * network call then fails, so the event is lost and no retry can find it. Publishing before the
 * write has the mirror problem: the event goes out and the transaction then rolls back, so the
 * subscriber acts on something that never happened.
 *
 * <p>Both are avoided by writing the event <em>in the same transaction</em> as the state change and
 * delivering it afterwards from the queue. The trade-off is at-least-once delivery, which every
 * consumer here is built to survive - every event carries a stable id and the append-only stores
 * reject an identical re-delivery rather than double-applying it.
 *
 * @param eventId       stable identity, used by consumers to deduplicate
 * @param topic         logical event name, e.g. {@code entitlement.revoked}
 * @param tenantId      tenant the event belongs to
 * @param aggregateType what the event is about, e.g. {@code INVOICE}
 * @param aggregateId   identity of the thing the event is about
 * @param payload       event body, already serialised
 * @param occurredAt    when the change happened (valid time)
 * @param createdAt     when the outbox row was written (system time)
 * @param attempts      delivery attempts made so far
 * @param nextAttemptAt when the next delivery is due; empty before the first attempt
 * @param lastError     the most recent failure message
 * @param deliveredAt   when delivery finally succeeded
 * @param signature     HMAC over the payload, so the receiver can prove authenticity
 * @param traceContext  the W3C trace context captured when the event was queued, so a trace
 *                      survives the asynchronous hop out of this process — see {@link TraceContext}
 */
public record OutboxEvent(
    String eventId,
    String topic,
    String tenantId,
    String aggregateType,
    String aggregateId,
    String payload,
    Instant occurredAt,
    Instant createdAt,
    int attempts,
    Optional<Instant> nextAttemptAt,
    Optional<String> lastError,
    Optional<Instant> deliveredAt,
    Optional<String> signature,
    TraceContext traceContext
) implements Serializable {

    /** Maximum delivery attempts before an event is treated as undeliverable. */
    public static final int MAX_ATTEMPTS = 12;

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Objects.requireNonNull(aggregateType, "aggregateType cannot be null");
        Objects.requireNonNull(aggregateId, "aggregateId cannot be null");
        Objects.requireNonNull(payload, "payload cannot be null");
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt cannot be null");
        Objects.requireNonNull(lastError, "lastError cannot be null");
        Objects.requireNonNull(deliveredAt, "deliveredAt cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        Objects.requireNonNull(traceContext, "traceContext cannot be null");

        if (attempts < 0) {
            throw new IllegalArgumentException("attempts cannot be negative");
        }
        if (payload.isBlank()) {
            throw new IllegalArgumentException("An outbox event needs a payload");
        }
    }

    /** A freshly queued event: never attempted, ready for immediate delivery. */
    public static OutboxEvent queued(String eventId, String topic, String tenantId,
                                     String aggregateType, String aggregateId, String payload,
                                     Instant occurredAt, Instant createdAt) {
        return new OutboxEvent(eventId, topic, tenantId, aggregateType, aggregateId, payload,
            occurredAt, createdAt, 0, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), TraceContext.NONE);
    }

    /** True once the event has been delivered successfully. */
    public boolean isDelivered() {
        return deliveredAt.isPresent();
    }

    /** True once the retry budget is spent and no further delivery will be attempted. */
    public boolean isExhausted() {
        return attempts >= MAX_ATTEMPTS && !isDelivered();
    }

    /** True when this event is eligible for a delivery attempt at {@code now}. */
    public boolean isDue(Instant now) {
        if (isDelivered() || isExhausted()) {
            return false;
        }
        return nextAttemptAt.map(due -> !now.isBefore(due)).orElse(true);
    }

    /** Records a successful delivery. */
    public OutboxEvent delivered(Instant at) {
        return new OutboxEvent(eventId, topic, tenantId, aggregateType, aggregateId, payload,
            occurredAt, createdAt, attempts, Optional.empty(), Optional.empty(), Optional.of(at),
            signature, traceContext);
    }

    /**
     * Records a failed attempt and schedules the next one.
     *
     * <p>Exhaustion is judged <em>after</em> incrementing, because it is this failure that may have
     * used the last attempt. Checking before the increment leaves an exhausted event holding a
     * next-attempt time it will never honour.
     */
    public OutboxEvent failed(String error, Instant nextAt) {
        int attemptsAfterThisFailure = attempts + 1;
        boolean exhausted = attemptsAfterThisFailure >= MAX_ATTEMPTS;
        return new OutboxEvent(eventId, topic, tenantId, aggregateType, aggregateId, payload,
            occurredAt, createdAt, attemptsAfterThisFailure,
            exhausted ? Optional.empty() : Optional.of(nextAt),
            Optional.of(error), Optional.empty(), signature, traceContext);
    }

    /** Returns a copy carrying an HMAC signature over the payload. */
    public OutboxEvent signed(String hmac) {
        return new OutboxEvent(eventId, topic, tenantId, aggregateType, aggregateId, payload,
            occurredAt, createdAt, attempts, nextAttemptAt, lastError, deliveredAt,
            Optional.of(hmac), traceContext);
    }

    /**
     * Returns a copy carrying exactly {@code context}.
     *
     * <p>Used by the repositories at enqueue time. Note the asymmetry that makes the outbox correct:
     * stamping happens once, when the event is queued inside the transaction that caused it, and
     * never again on a delivery pass. Re-reading the trace at dispatch would replace the link to
     * the request that made the state change with a link to the dispatcher's own poll loop, which
     * is not an association anyone would ask for.</p>
     */
    public OutboxEvent withTraceContext(TraceContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return new OutboxEvent(eventId, topic, tenantId, aggregateType, aggregateId, payload,
            occurredAt, createdAt, attempts, nextAttemptAt, lastError, deliveredAt, signature, context);
    }

    /**
     * Whether {@code other} is the same event, ignoring where either was queued from.
     *
     * <p>Trace context is deliberately excluded, and it has to be. A retried transaction re-writes
     * the same event id, and that retry may run on a thread with no trace active — or under a
     * different one. Comparing the trace context as content would refuse the second write as a
     * conflict, turning a harmless retry into a failure inside the caller's transaction. It would
     * also be conceptually wrong: {@code traceparent} records which request caused an event, not
     * what the event is, so two writes that differ only in it are the same event.</p>
     *
     * <p>This is why the JDBC adapter's conflict check compares {@code payload} and {@code topic}
     * only, and why the in-memory one has to match it rather than comparing whole records.</p>
     */
    public boolean hasSameContentAs(OutboxEvent other) {
        Objects.requireNonNull(other, "other cannot be null");
        return withTraceContext(TraceContext.NONE).equals(other.withTraceContext(TraceContext.NONE));
    }
}