-- ==============================================================================
-- Flyway Migration V17: idempotency-key enforcement (PostgreSQL only)
--
-- V16 creates the table and its column constraints. This closes the two holes an
-- attacker or a bug could otherwise drive through:
--
--   1. Identity is immutable. tenant_id, idem_key and fingerprint identify which
--      request this row answers. If an UPDATE could move them, one request's
--      recorded response could be replayed for a different one - exactly the
--      cross-request confusion the fingerprint exists to prevent.
--
--   2. A live claim cannot be stolen or regressed. status may only move
--      IN_FLIGHT -> COMPLETED, or back to IN_FLIGHT when the claim has expired
--      (a reclaim by another request after the TTL). Without the expiry gate a
--      concurrent UPDATE could reset a live claim and let two executions proceed.
--
-- PostgreSQL-only (plpgsql), so it is excluded from the H2 test fixture - see
-- BaseJdbcRepositoryTest. Verified against a real PostgreSQL by PostgresMigrationTest.
-- ==============================================================================

CREATE OR REPLACE FUNCTION idempotency_key_guards() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.tenant_id <> NEW.tenant_id OR OLD.idem_key <> NEW.idem_key THEN
            RAISE EXCEPTION
                'Idempotency key identity is immutable (tenant %/key %)',
                OLD.tenant_id, OLD.idem_key;
        END IF;

        -- A fingerprint may change only on a reclaim of an expired claim, which rewrites the row
        -- for a new execution of the same key.
        IF OLD.fingerprint <> NEW.fingerprint AND OLD.expires_at > CURRENT_TIMESTAMP THEN
            RAISE EXCEPTION
                'Fingerprint for idempotency key %/% may only change when the claim has expired',
                OLD.tenant_id, OLD.idem_key;
        END IF;

        -- COMPLETED -> IN_FLIGHT is a reclaim, and only an expired claim may be reclaimed.
        IF OLD.status = 'COMPLETED' AND NEW.status = 'IN_FLIGHT'
           AND OLD.expires_at > CURRENT_TIMESTAMP THEN
            RAISE EXCEPTION
                'Idempotency key %/% is completed and still live; it cannot be reclaimed',
                OLD.tenant_id, OLD.idem_key;
        END IF;
    END IF;

    -- Holds for INSERT too: a claim that expires before it is recorded can never be used.
    IF NEW.expires_at <= NEW.recorded_at THEN
        RAISE EXCEPTION
            'Idempotency key %/% expires_at must be after recorded_at',
            NEW.tenant_id, NEW.idem_key;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_idempotency_key_update_guards ON idempotency_keys;

CREATE TRIGGER trg_idempotency_key_update_guards
    BEFORE INSERT OR UPDATE ON idempotency_keys
    FOR EACH ROW EXECUTE FUNCTION idempotency_key_guards();
