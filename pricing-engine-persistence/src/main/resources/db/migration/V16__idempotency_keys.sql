-- ==============================================================================
-- Flyway Migration V16: HTTP idempotency keys
--
-- Implements the IETF Idempotency-Key header contract for money-moving endpoints. The draft is
-- explicit that duplicate records "involving any kind of money transfer MUST NOT be allowed", and a
-- retried POST /invoices that issues a second invoice is exactly that.
--
-- The PRIMARY KEY is the whole mechanism. A claim is a single INSERT, so two concurrent requests
-- carrying the same key cannot both succeed: the loser collides and is told to wait. A
-- check-then-insert would let both through.
--
-- The key is scoped by tenant. Idempotency keys are chosen by clients and plausible ones collide
-- (a date-based key, a timestamped UUID, a key reused by a retrying proxy). Without tenant_id in
-- the primary key, tenant B reusing a key tenant A already used is served tenant A's stored
-- RESPONSE BODY -- a cross-tenant data leak, not merely a spurious conflict.
--
-- recorded_at doubles as the fencing token. A request that outlives its TTL may find the row
-- already reclaimed and re-executed; completing or releasing on (tenant_id, idem_key) alone would
-- let that slow original overwrite or delete the newer execution's result.
--
-- Portable SQL only; PostgreSQL-only enforcement lives in V17.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS idempotency_keys (
    tenant_id      VARCHAR(64)  NOT NULL,
    idem_key       VARCHAR(255) NOT NULL,

    -- Digest of the request body. Lets the server tell a retry (replay the result) from the same
    -- key reused for a different request (409/422 rather than a misleading replay).
    fingerprint     VARCHAR(64)  NOT NULL,

    status          VARCHAR(16)  NOT NULL,

    -- The original response, replayed verbatim - including its original error status.
    response_status INTEGER      NOT NULL DEFAULT 0,
    response_body   TEXT         NOT NULL DEFAULT '',

    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (tenant_id, idem_key),

    CONSTRAINT ck_idempotency_status
        CHECK (status IN ('IN_FLIGHT', 'COMPLETED')),

    -- A completed entry must carry the response it will replay; an in-flight one must not claim to.
    CONSTRAINT ck_idempotency_completed_has_response
        CHECK (status <> 'COMPLETED'
            OR (response_status >= 100 AND response_status <= 599)),

    CONSTRAINT ck_idempotency_in_flight_empty_response
        CHECK (status <> 'IN_FLIGHT' OR (response_status = 0 AND response_body = '')),

    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > recorded_at)
);

-- Expiry sweep: a wedged claim must not block a client forever.
CREATE INDEX IF NOT EXISTS idx_idempotency_expiry
    ON idempotency_keys (expires_at);

-- The reclaim predicate filters on expiry *and* the primary key, so it leads.
CREATE INDEX IF NOT EXISTS idx_idempotency_status
    ON idempotency_keys (tenant_id, idem_key, status);