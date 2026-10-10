-- ==============================================================================
-- Flyway Migration V22: outbox trace context
--
-- W3C Trace Context propagation headers, stored on the event row.
--
-- Why this exists: the outbox is an asynchronous boundary, and trace context does not cross one
-- on its own. The request that finalised an invoice opens a trace; the event announcing it is
-- written here and picked up later by a dispatcher, possibly in another process. Without the
-- context on the row, the trace stops at the database commit and the consumer's span is orphaned
-- -- which is the worst place to lose a trace, because the question people actually ask is "why
-- did this subscriber receive this event".
--
-- Every mature outbox does this: a traceparent column lifted into the broker message header by
-- the relay, or an event-routing SMT doing the same. This is the part that makes those possible.
--
-- Nullable, so existing rows stay valid: they were written before tracing and carry no trace.
--
-- Portable SQL only.
-- ==============================================================================

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS traceparent VARCHAR(55);

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS tracestate VARCHAR(512);