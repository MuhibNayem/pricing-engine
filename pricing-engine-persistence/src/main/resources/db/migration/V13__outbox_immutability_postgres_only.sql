-- ==============================================================================
-- Flyway Migration V13: outbox payload immutability (PostgreSQL only)
--
-- V12 creates the outbox. This protects the part of each row that must never
-- move: what was said, to whom, and when it happened. Only the delivery
-- bookkeeping (attempts, errors, delivered_at, signature) may change, because
-- rewriting an event after the fact would mean a subscriber's history no longer
-- matches what they were actually told.
--
-- PostgreSQL-only (plpgsql triggers), so it is excluded from the H2 test fixture -
-- see BaseJdbcRepositoryTest. Verified against a real PostgreSQL by
-- PostgresMigrationTest.
--
-- DELETE raises rather than being silently ignored: a rule that does nothing
-- makes a deletion look like it succeeded. An undelivered event that will never
-- be delivered is precisely what an operator needs to find; write it off by
-- marking it delivered with an error instead.
-- ==============================================================================

DROP RULE IF EXISTS outbox_events_no_delete ON outbox_events;

CREATE OR REPLACE FUNCTION outbox_events_reject_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'outbox_events is append-only: DELETE is rejected; mark the event delivered with an error instead';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_outbox_events_no_delete ON outbox_events;
CREATE TRIGGER trg_outbox_events_no_delete
    BEFORE DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION outbox_events_reject_delete();

-- The function and trigger are declared at the top level, never inside a dollar-quoted DO block:
-- a plpgsql body cannot be nested inside another dollar-quoted block (the inner '$$' terminates
-- the outer one), which made an earlier revision of this file a syntax error on PostgreSQL.
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
