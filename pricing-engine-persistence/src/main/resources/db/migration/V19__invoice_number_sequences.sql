-- ==============================================================================
-- Flyway Migration V19: invoice number sequences
--
-- Backed by one row per series (tenant + series key), updated in place.
--
-- The allocation is a row lock plus an update, not a read-then-write. Invoice number
-- allocation is the one place where check-then-increment is unacceptable: two concurrent
-- finalizations that both read "next = 41" would both write 42, and a duplicated document
-- number is the most audit-visible defect a billing system can produce. It also breaks the
-- tax-year continuity that ACCOUNT_SEQUENTIAL numbering exists to prove.
--
-- The series is keyed by customer id as well as tenant, because a per-customer scheme is
-- genuinely per-customer: one tenant's series must never consume another's.
--
-- start_at exists so a tenant migrating from another billing system can RESUME a series
-- instead of restarting at 1. It is never lowered below a number already issued.
--
-- Portable SQL only.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS invoice_number_sequences (
    tenant_id     VARCHAR(64)  NOT NULL,
    sequence_key  VARCHAR(128) NOT NULL,

    -- Highest value handed out so far. 0 means "nothing issued yet".
    next_value    BIGINT       NOT NULL DEFAULT 0,

    -- Where this series begins. Migration continuity: a tenant coming from another system
    -- sets this to the next unused number rather than reusing numbers that already exist
    -- in the old system's history.
    start_at      BIGINT       NOT NULL DEFAULT 1,

    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_invoice_number_sequences PRIMARY KEY (tenant_id, sequence_key),

    CONSTRAINT ck_invoice_sequence_positive CHECK (next_value >= 0),
    CONSTRAINT ck_invoice_sequence_start CHECK (start_at >= 1),

    -- A series may never be positioned before its own start, or the first invoices issued
    -- would collide with the numbering the tenant migrated from.
    CONSTRAINT ck_invoice_sequence_at_or_above_start CHECK (next_value >= start_at - 1)
);

-- The sweep below is a whole-platform lookup, ordered by staleness.
CREATE INDEX IF NOT EXISTS idx_invoice_sequences_tenant
    ON invoice_number_sequences (tenant_id);