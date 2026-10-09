-- ==============================================================================
-- Flyway Migration V9: entitlement event immutability (PostgreSQL only)
--
-- V8 creates the stream and its column constraints. This closes what those
-- cannot express: that history may only grow.
--
-- WHY A SEPARATE FILE
--
-- It uses plpgsql triggers, which H2 does not implement. V8 is portable and fully
-- exercised by the automated tests; THIS script is verified against real
-- PostgreSQL by PostgresMigrationTest.
--
-- UPDATE and DELETE raise rather than being silently ignored: a rule that does
-- nothing makes a mutation look like it succeeded, which is worse for an audit
-- than an outright refusal.
-- ==============================================================================

DROP RULE IF EXISTS entitlement_events_no_update ON entitlement_events;
DROP RULE IF EXISTS entitlement_events_no_delete ON entitlement_events;

CREATE OR REPLACE FUNCTION entitlement_events_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'entitlement_events is append-only: % is rejected; record a new event instead',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_entitlement_events_no_update ON entitlement_events;
CREATE TRIGGER trg_entitlement_events_no_update
    BEFORE UPDATE ON entitlement_events
    FOR EACH ROW EXECUTE FUNCTION entitlement_events_reject_mutation();

DROP TRIGGER IF EXISTS trg_entitlement_events_no_delete ON entitlement_events;
CREATE TRIGGER trg_entitlement_events_no_delete
    BEFORE DELETE ON entitlement_events
    FOR EACH ROW EXECUTE FUNCTION entitlement_events_reject_mutation();

-- A feature may not be revoked twice in a row. A duplicate revocation is a redelivery bug that
-- would otherwise reach the customer as a second, spurious access-revocation notice.
--
-- Excluding NEW.event_id is the whole point: this is an AFTER INSERT trigger, so without the
-- exclusion the row being inserted matches its own EXISTS and EVERY revocation is rejected.
CREATE OR REPLACE FUNCTION entitlement_no_double_revoke() RETURNS trigger AS $$
BEGIN
    IF NEW.change_type <> 'REVOKED' THEN
        RETURN NEW;
    END IF;

    IF EXISTS (
        SELECT 1 FROM entitlement_events
        WHERE tenant_id = NEW.tenant_id
          AND customer_id = NEW.customer_id
          AND feature_key = NEW.feature_key
          AND change_type = 'REVOKED'
          AND event_id <> NEW.event_id
    ) THEN
        RAISE EXCEPTION
            'Feature % for customer %/% has already been revoked',
            NEW.feature_key, NEW.tenant_id, NEW.customer_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_entitlement_no_double_revoke ON entitlement_events;

CREATE CONSTRAINT TRIGGER trg_entitlement_no_double_revoke
    AFTER INSERT ON entitlement_events
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION entitlement_no_double_revoke();
