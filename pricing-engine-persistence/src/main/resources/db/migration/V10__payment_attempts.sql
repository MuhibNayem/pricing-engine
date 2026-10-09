-- ==============================================================================
-- Flyway Migration V10: payment attempts (collection ledger)
--
-- An OPEN invoice is a claim, not a receipt. Without a record of what was
-- attempted and when, an invoice can sit in OPEN forever while money is never
-- collected, and nobody can tell a genuinely-disputed invoice from one that was
-- quietly never chased.
--
-- Attempts are append-only, exactly like the wallet ledger: a failed collection
-- is corrected by recording the next attempt, never by editing history. The
-- attempt_id is derived from (invoice, attempt number) rather than random, so a
-- collection agent that times out and re-sends is recognisably the SAME charge
-- rather than a second one.
--
-- Portable SQL only; append-only enforcement lives in V11 because H2 cannot
-- execute PostgreSQL rules.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS payment_attempts (
    attempt_id        VARCHAR(128) PRIMARY KEY,
    invoice_id        VARCHAR(128) NOT NULL,
    attempt_number    INTEGER      NOT NULL,

    amount            NUMERIC(24, 8) NOT NULL,
    currency          VARCHAR(16)  NOT NULL,
    status            VARCHAR(32)  NOT NULL,

    -- A processor decline code. Required for a failure, and without it the decline cannot be
    -- explained to the customer or to the acquiring bank.
    failure_code      VARCHAR(64),
    failure_reason    VARCHAR(512),

    attempted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at   TIMESTAMP WITH TIME ZONE,

    payload_json      TEXT         NOT NULL DEFAULT '{}',
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_attempt_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (invoice_id) ON DELETE RESTRICT,

    CONSTRAINT ck_payment_attempt_status
        CHECK (status IN ('SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_TERMINAL')),

    -- Attempt numbers are 1-based and unique per invoice; that pairing is what makes the derived
    -- attempt id unique without inventing a random one.
    CONSTRAINT ck_payment_attempt_number CHECK (attempt_number >= 1),

    CONSTRAINT uq_payment_attempt_number UNIQUE (invoice_id, attempt_number),

    CONSTRAINT ck_payment_attempt_amount CHECK (amount >= 0),

    -- A failure must carry the processor's code; success must not schedule a further attempt.
    CONSTRAINT ck_payment_attempt_failure_code
        CHECK (status = 'SUCCEEDED' OR (failure_code IS NOT NULL AND LENGTH(TRIM(failure_code)) > 0)),

    CONSTRAINT ck_payment_attempt_retry_scheduled
        CHECK (status <> 'FAILED_RETRYABLE' OR next_attempt_at IS NOT NULL),

    CONSTRAINT ck_payment_attempt_success_no_retry
        CHECK (status <> 'SUCCEEDED' OR next_attempt_at IS NULL),

    CONSTRAINT ck_payment_attempt_next_after
        CHECK (next_attempt_at IS NULL OR next_attempt_at >= attempted_at)
);

-- The collection loop reads one invoice's attempts in order.
CREATE INDEX IF NOT EXISTS idx_payment_attempts_invoice
    ON payment_attempts (invoice_id, attempt_number);

-- A scheduled job finds everything due for retry across all invoices. A plain index rather
-- than a partial one, because partial indexes are not portable to the H2 build used by the test
-- suite; the query filters on status itself.
CREATE INDEX IF NOT EXISTS idx_payment_attempts_due
    ON payment_attempts (next_attempt_at, invoice_id);