-- ==============================================================================
-- Flyway Migration V18: subscription version
--
-- A per-aggregate transition counter, used as the sequence number for a
-- subscription's events in the outbox.
--
-- Why this exists: without a per-aggregate sequence, anything derived from the
-- state eventually repeats. Pause, resume, pause again inside one billing period
-- all land on the same state, so an event id built from the state collides on
-- the second pause. The outbox refuses a duplicate id carrying different content
-- and silently drops an identical one -- so the second pause is never announced.
-- The row looks correct; no subscriber ever heard about it.
--
-- Default 0 so existing rows are valid: they were created before versioning and
-- have had no transitions counted against them.
--
-- Portable SQL only.
-- ==============================================================================

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- A negative version is meaningless and would break event-id uniqueness reasoning.
ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS ck_subscription_version;

ALTER TABLE subscriptions
    ADD CONSTRAINT ck_subscription_version CHECK (version >= 0);