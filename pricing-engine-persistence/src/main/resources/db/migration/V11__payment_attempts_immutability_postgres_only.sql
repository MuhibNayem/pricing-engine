-- ==============================================================================
-- Flyway Migration V11: payment attempt immutability (PostgreSQL only)
--
-- V10 creates the collection ledger and its constraints. This stops history being
-- rewritten: a collection agent or an operator cannot quietly delete a failed
-- attempt to make an invoice look like it was never chased.
--
-- PostgreSQL-only (plpgsql triggers), so it is excluded from the H2 test fixture -
-- see BaseJdbcRepositoryTest. The Java layer enforces the same rule, so CI covers
-- the application path; this is the defence-in-depth layer, verified against a
-- real PostgreSQL by PostgresMigrationTest.
--
-- UPDATE and DELETE raise rather than being silently ignored: a rule that does
-- nothing makes a mutation look like it succeeded, which is worse for an audit
-- than an outright refusal.
-- ==============================================================================

DROP RULE IF EXISTS payment_attempts_no_update ON payment_attempts;
DROP RULE IF EXISTS payment_attempts_no_delete ON payment_attempts;

CREATE OR REPLACE FUNCTION payment_attempts_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'payment_attempts is append-only: % is rejected; record the next attempt instead',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payment_attempts_no_update ON payment_attempts;
CREATE TRIGGER trg_payment_attempts_no_update
    BEFORE UPDATE ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION payment_attempts_reject_mutation();

DROP TRIGGER IF EXISTS trg_payment_attempts_no_delete ON payment_attempts;
CREATE TRIGGER trg_payment_attempts_no_delete
    BEFORE DELETE ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION payment_attempts_reject_mutation();

-- An invoice must not be collected for the same attempt number twice.
--
-- The derived attempt id already makes this hard to hit from the application, but a constraint is
-- what actually stops two concurrent collection workers from both deciding attempt 3 is due and
-- both taking the customer's money.
CREATE OR REPLACE FUNCTION payment_attempt_number_unique() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM payment_attempts
        WHERE invoice_id = NEW.invoice_id
          AND attempt_number = NEW.attempt_number
          AND attempt_id <> NEW.attempt_id
    ) THEN
        RAISE EXCEPTION
            'Attempt % for invoice % has already been recorded',
            NEW.attempt_number, NEW.invoice_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payment_attempt_number_unique ON payment_attempts;

CREATE CONSTRAINT TRIGGER trg_payment_attempt_number_unique
    AFTER INSERT ON payment_attempts
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION payment_attempt_number_unique();
