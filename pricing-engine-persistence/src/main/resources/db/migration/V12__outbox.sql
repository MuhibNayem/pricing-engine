-- ==============================================================================
-- Flyway Migration V12: transactional outbox
--
-- Events are written in the SAME transaction as the state change they describe.
-- That is the only arrangement that makes "the state changed but nobody was told"
-- impossible: publishing inline loses the event when the network fails after the
-- commit, and publishing before the write notifies subscribers of a change that
-- then rolls back.
--
-- The trade-off is at-least-once delivery. Every event carries a stable event_id,
-- and every append-only store in this schema rejects an identical re-delivery rather
-- than applying it twice, so consumers are safe to retry.
--
-- Portable SQL only; the append-only enforcement lives in V13.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id        VARCHAR(128) PRIMARY KEY,
    topic           VARCHAR(128) NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,

    -- Valid time: when the change happened.
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- System time: when this row was written.
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(1024),
    delivered_at    TIMESTAMP WITH TIME ZONE,
    signature       VARCHAR(256),

    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),

    -- A delivered event must not still be scheduled, and a retryable one must be.
    CONSTRAINT ck_outbox_delivered_no_retry
        CHECK (delivered_at IS NULL OR next_attempt_at IS NULL),

    -- An event cannot be both delivered and carrying an error. Kept deliberately simple: an
    -- over-clever version of this rule rejects a perfectly ordinary first-time delivery.
    CONSTRAINT ck_outbox_delivered_without_error
        CHECK (delivered_at IS NULL OR last_error IS NULL)
);

-- The dispatcher polls this. A plain index rather than a partial one, because partial indexes are
-- not portable to the H2 build the test suite uses; the query filters on the columns itself.
CREATE INDEX IF NOT EXISTS idx_outbox_due
    ON outbox_events (next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_topic_state
    ON outbox_events (topic, delivered_at, created_at);

-- Per-tenant history, for an operator replaying or auditing.
CREATE INDEX IF NOT EXISTS idx_outbox_tenant
    ON outbox_events (tenant_id, created_at);

-- Aggregate lookups, e.g. "every event for invoice inv-1".
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);