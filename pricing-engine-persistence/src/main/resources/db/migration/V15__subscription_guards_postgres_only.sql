-- ==============================================================================
-- Flyway Migration V15: subscription update guards (PostgreSQL only)
--
-- V14 creates the subscriptions table with portable column constraints. This adds
-- what those cannot express: the two invariants of the aggregate's state machine.
--
--   1. Version monotonicity. Every model transition advances `version` by exactly
--      one, and the repository's optimistic lock relies on that. A row that could
--      keep its version while its state moved would make the lock match a stale
--      writer and lose the newer transition (and its outbox event).
--
--   2. No resurrection. CANCELED and EXPIRED are terminal. A cancelled
--      subscription that comes back to life is the exact failure the terminal
--      states exist to prevent: the customer regains access they stopped paying
--      for, and a credit note already issued can never be reconciled.
--
-- PostgreSQL-only (plpgsql), so it is excluded from the H2 test fixture - see
-- BaseJdbcRepositoryTest. Verified against a real PostgreSQL by PostgresMigrationTest.
-- ==============================================================================

CREATE OR REPLACE FUNCTION subscription_update_guards() RETURNS trigger AS $$
BEGIN
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION
            'Subscription % version must advance by exactly one (got % -> %)',
            NEW.subscription_id, OLD.version, NEW.version;
    END IF;

    IF OLD.status IN ('CANCELED', 'EXPIRED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION
            'Subscription % is in terminal state %; transition to % is not legal',
            NEW.subscription_id, OLD.status, NEW.status;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_subscription_update_guards ON subscriptions;

CREATE TRIGGER trg_subscription_update_guards
    BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION subscription_update_guards();
