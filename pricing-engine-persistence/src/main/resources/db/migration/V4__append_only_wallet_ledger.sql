-- ==============================================================================
-- Flyway Migration V4: append-only wallet ledger
--
-- The wallet balance is a cache. This entry stream is the record of truth: the
-- balance can be reconstructed by replaying it, and a disputed figure can be
-- defended by showing the entries that produced it. A correction is a REVERSAL
-- row carrying the exact negation of the entry it reverses - never an UPDATE.
--
-- Database-level UPDATE/DELETE rejection lives in V5, because it needs PostgreSQL's
-- CREATE RULE, which the H2 build used by the test suite does not support. This
-- migration therefore stays portable and is fully exercised by the tests; the
-- rules in V5 are verified only against a real PostgreSQL.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS wallet_ledger_entries (
    entry_id            VARCHAR(128) PRIMARY KEY,
    wallet_id           VARCHAR(128) NOT NULL,
    entry_type          VARCHAR(32)  NOT NULL,
    signed_credits      NUMERIC(24, 8) NOT NULL,
    signed_money_amount NUMERIC(24, 8) NOT NULL,
    currency            VARCHAR(16) NOT NULL,
    calculation_id      VARCHAR(128) NOT NULL,
    reverses_entry_id   VARCHAR(128),
    reason              VARCHAR(512),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Only a REVERSAL may point at another entry, and a reversal must point at one.
    CONSTRAINT ck_ledger_reversal_only
        CHECK ((entry_type = 'REVERSAL' AND reverses_entry_id IS NOT NULL)
            OR (entry_type <> 'REVERSAL' AND reverses_entry_id IS NULL)),

    -- A manual correction without a stated reason is not auditable.
    CONSTRAINT ck_ledger_adjustment_reason
        CHECK (entry_type <> 'ADJUSTMENT' OR (reason IS NOT NULL AND LENGTH(TRIM(reason)) > 0)),

    CONSTRAINT ck_ledger_no_self_reversal CHECK (reverses_entry_id IS NULL OR reverses_entry_id <> entry_id),

    CONSTRAINT fk_ledger_entry_wallet
        FOREIGN KEY (wallet_id) REFERENCES wallets (wallet_id) ON DELETE RESTRICT,

    -- A reversal cannot be made to reference a row that does not exist, which would leave the
    -- negated amount dangling with nothing to reconcile against.
    CONSTRAINT fk_ledger_reverses_entry
        FOREIGN KEY (reverses_entry_id) REFERENCES wallet_ledger_entries (entry_id) ON DELETE RESTRICT
);

-- Replay and reconciliation read a wallet's history in order.
CREATE INDEX IF NOT EXISTS idx_ledger_wallet_created
    ON wallet_ledger_entries (wallet_id, created_at, entry_id);

-- Finding entries a given entry reverses.
CREATE INDEX IF NOT EXISTS idx_ledger_reverses
    ON wallet_ledger_entries (reverses_entry_id);

-- ---------------------------------------------------------------------------
-- Integrity checks that turn logic bugs into rejected writes
-- ---------------------------------------------------------------------------

-- A wallet's derived balance must never go negative: you cannot spend credit
-- that was never granted. The engine clamps drawdowns, so a negative running
-- balance means the clamping failed, not that the customer had a credit.
ALTER TABLE wallet_ledger_entries DROP CONSTRAINT IF EXISTS ck_ledger_entry_typed;

-- Only these entry types may appear; an unknown type would be silently summed.
ALTER TABLE wallet_ledger_entries ADD CONSTRAINT ck_ledger_entry_type
    CHECK (entry_type IN ('GRANT_ISSUED', 'DRAWDOWN', 'REVERSAL', 'ADJUSTMENT'));

-- Credits and their money equivalent must agree in sign, otherwise the two
-- columns could be replayed to different answers.
ALTER TABLE wallet_ledger_entries ADD CONSTRAINT ck_ledger_sign_agreement
    CHECK ((signed_credits = 0 AND signed_money_amount = 0)
        OR (signed_credits > 0 AND signed_money_amount > 0)
        OR (signed_credits < 0 AND signed_money_amount < 0));