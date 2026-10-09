-- ==============================================================================
-- Flyway Migration V11: payment attempt immutability (PostgreSQL only)
--
-- V10 creates the collection ledger and its constraints. This stops history being
-- rewritten: a collection agent or an operator cannot quietly delete a failed
-- attempt to make an invoice look like it was never chased.
--
-- PostgreSQL-only (CREATE RULE), so it is excluded from the H2 test fixture -
-- see BaseJdbcRepositoryTest, which loads V1-V4, V6, V8 and V10 but not V5, V7,
-- V9 or V11. The Java layer enforces the same rule, so CI covers the application
-- path; this is the defence-in-depth layer, verified only against PostgreSQL.
-- ==============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_rules
        WHERE schemaname = current_schema() AND tablename = 'payment_attempts'
    ) THEN

        CREATE RULE payment_attempts_no_update AS
            ON UPDATE TO payment_attempts
            DO INSTEAD NOTHING;

        CREATE RULE payment_attempts_no_delete AS
            ON DELETE TO payment_attempts
            DO INSTEAD NOTHING;

    END IF;
END
$$;

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
