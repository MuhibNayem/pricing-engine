-- ==============================================================================
-- Flyway Migration V13: outbox payload immutability (PostgreSQL only)
--
-- V12 creates the outbox. This protects the part of each row that must never
-- move: what was said, to whom, and when it happened. Only the delivery
-- bookkeeping (attempts, errors, delivered_at, signature) may change, because
-- rewriting an event after the fact would mean a subscriber's history no longer
-- matches what they were actually told.
--
-- PostgreSQL-only (CREATE RULE / plpgsql), so it is excluded from the H2 test
-- fixture - see BaseJdbcRepositoryTest.
-- ==============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_rules
        WHERE schemaname = current_schema() AND tablename = 'outbox_events'
    ) THEN

        -- No DELETE at all: an undelivered event that will never be delivered is precisely what
        -- an operator needs to find, so removing it would hide a committed change nobody was told
        -- about. Write off by marking it delivered with an error instead.
        CREATE RULE outbox_events_no_delete AS
            ON DELETE TO outbox_events
            DO INSTEAD NOTHING;

    END IF;
END
$$;

-- The function and trigger are declared OUTSIDE the DO block on purpose. A dollar-quoted
-- plpgsql body cannot be nested inside another dollar-quoted DO block - the inner '$$'
-- terminates the outer block and the script is a syntax error. Keeping them here also means
-- the trigger is (re)installed unconditionally, so a partially applied earlier run cannot
-- leave the payload unguarded.
CREATE OR REPLACE FUNCTION outbox_payload_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.event_id     IS DISTINCT FROM NEW.event_id
       OR OLD.topic      IS DISTINCT FROM NEW.topic
       OR OLD.tenant_id  IS DISTINCT FROM NEW.tenant_id
       OR OLD.aggregate_type IS DISTINCT FROM NEW.aggregate_type
       OR OLD.aggregate_id   IS DISTINCT FROM NEW.aggregate_id
       OR OLD.payload        IS DISTINCT FROM NEW.payload
       OR OLD.occurred_at    IS DISTINCT FROM NEW.occurred_at THEN
        RAISE EXCEPTION
            'Outbox event % is immutable; only delivery bookkeeping may be updated',
            OLD.event_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_outbox_payload_immutable ON outbox_events;

CREATE TRIGGER trg_outbox_payload_immutable
    BEFORE UPDATE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION outbox_payload_immutable();
