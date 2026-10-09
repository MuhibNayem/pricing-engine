-- ==============================================================================
-- Flyway Migration V3: integrity constraints, concurrency support, and idempotency
--
-- Additive only: V1 and V2 are already released, so every change here is an
-- ALTER/CREATE rather than an edit of a shipped migration.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Meter aggregation: carry the "approximate" flag
--
-- A DISTINCT_COUNT aggregation that exceeds its cardinality cap cannot report an
-- exact count. Without a persisted flag, an undercounted aggregation round-trips
-- through the database and is then billed as if it were exact - i.e. the cap
-- silently turns into revenue leakage. The flag must survive persistence.
-- ------------------------------------------------------------------------------
ALTER TABLE meter_aggregations ADD COLUMN IF NOT EXISTS approximate BOOLEAN NOT NULL DEFAULT FALSE;

-- ------------------------------------------------------------------------------
-- 2. Dedicated idempotency-key table for an atomic claim
--
-- checkAndRecord previously ran SELECT COUNT(*) against meter_events and recorded
-- nothing, so two concurrent callers both observed "not present" and both were
-- admitted. The unique constraint on meter_events was the only thing preventing a
-- double charge, which means the store's own answer was wrong even though the
-- system as a whole happened to be protected.
--
-- A dedicated table makes the claim itself the atomic operation: INSERT either
-- succeeds (we own the key) or it violates the primary key (someone else does).
-- Plain INSERT is used rather than INSERT ... ON CONFLICT because the latter is
-- rejected by the H2 version the test suite runs against, even in PostgreSQL mode.
--
-- recorded_at is retained so a failed claim can be released exactly, without
-- deleting a row that a different caller has since replaced.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meter_idempotency_keys (
    tenant_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_meter_idempotency_keys PRIMARY KEY (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_mik_recorded_at ON meter_idempotency_keys (recorded_at);

-- ------------------------------------------------------------------------------
-- 3. Wallet optimistic-locking version
--
-- updateAtomically() serialises the read-modify-write (row lock in JDBC, map
-- compute in memory), so the version column is not required for correctness there.
-- It exists so a caller can also take the optimistic path, and so that a stale
-- write can be detected rather than silently overwriting a newer balance.
-- ------------------------------------------------------------------------------
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- ------------------------------------------------------------------------------
-- 4. Referential integrity
--
-- wallet_transactions.wallet_id was an unconstrained VARCHAR: a ledger entry
-- could reference a wallet that does not exist, and deleting a wallet left its
-- ledger rows orphaned. The ledger is financial evidence, so it must not dangle.
-- ------------------------------------------------------------------------------
DELETE FROM wallet_transactions wt
    WHERE NOT EXISTS (SELECT 1 FROM wallets w WHERE w.wallet_id = wt.wallet_id);

ALTER TABLE wallet_transactions
    ADD CONSTRAINT fk_wallet_transactions_wallet
    FOREIGN KEY (wallet_id) REFERENCES wallets (wallet_id) ON DELETE RESTRICT;

-- ------------------------------------------------------------------------------
-- 5. Domain invariants
--
-- These are cheap to write and they turn a whole class of logic bug into a
-- rejected write at the boundary rather than a wrong invoice discovered later.
-- ------------------------------------------------------------------------------

-- A window must have positive duration; an inverted window silently aggregates
-- nothing and bills zero.
ALTER TABLE meter_aggregations
    ADD CONSTRAINT ck_meter_agg_window_positive CHECK (window_end > window_start);

ALTER TABLE meter_aggregations
    ADD CONSTRAINT ck_meter_agg_event_count CHECK (event_count >= 0);

-- Effective-dated records must not end before they begin.
ALTER TABLE rate_cards
    ADD CONSTRAINT ck_rate_cards_effective_range
    CHECK (effective_to IS NULL OR effective_to > effective_from);

ALTER TABLE contract_overrides
    ADD CONSTRAINT ck_contract_overrides_effective_range
    CHECK (effective_to IS NULL OR effective_to > effective_from);

ALTER TABLE entitlements
    ADD CONSTRAINT ck_entitlements_effective_range
    CHECK (effective_to IS NULL OR effective_to > effective_from);

-- A quota cannot be negative, and consumed usage cannot be negative.
ALTER TABLE entitlements
    ADD CONSTRAINT ck_entitlements_quota_non_negative
    CHECK (quota_limit IS NULL OR quota_limit >= 0);

ALTER TABLE entitlements
    ADD CONSTRAINT ck_entitlements_usage_non_negative
    CHECK (current_usage >= 0);

-- A card cannot be superseded before it was recorded.
ALTER TABLE rate_cards
    ADD CONSTRAINT ck_rate_cards_supersession
    CHECK (superseded_at IS NULL OR superseded_at >= recorded_at);

ALTER TABLE contract_overrides
    ADD CONSTRAINT ck_contract_overrides_supersession
    CHECK (superseded_at IS NULL OR superseded_at >= recorded_at);