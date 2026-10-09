-- ==============================================================================
-- Flyway Migration V21: content fingerprint for meter idempotency keys
--
-- V3 gave the claim table an atomic INSERT but no memory of what the key MEANT.
-- A reused key with different content was answered "duplicate", silently dropping
-- an event the caller believes was recorded. The fingerprint lets the store answer
-- DUPLICATE (identical retry) or CONFLICT (key reused with different content).
--
-- Existing rows default to the empty fingerprint, which compares unequal to any
-- real digest: a pre-migration key reused with different content is reported as a
-- conflict rather than a silent duplicate.
-- ==============================================================================

ALTER TABLE meter_idempotency_keys
    ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(128) NOT NULL DEFAULT '';
