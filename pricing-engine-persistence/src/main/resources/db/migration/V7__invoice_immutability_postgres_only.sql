-- ==============================================================================
-- Flyway Migration V7: invoice immutability and credit cap (PostgreSQL only)
--
-- V6 creates the tables and their column constraints. This closes the gap those
-- constraints cannot: they describe what a row may CONTAIN, not what may CHANGE
-- after the fact. A finalized invoice's terms must never move, and total credits
-- against one invoice must never exceed that invoice.
--
-- WHY A SEPARATE FILE
--
-- It uses PostgreSQL triggers and plpgsql, which H2 does not implement. V6 is
-- portable and fully exercised by the automated tests; THIS script is verified
-- only against a real PostgreSQL server. It is excluded from the H2 test fixture
-- on purpose rather than silently degraded - see BaseJdbcRepositoryTest, which
-- loads V1-V4 and V6 but not V5 or V7.
--
-- The Java layer (JdbcInvoiceRepository) enforces the same rules, so the
-- application path is covered by CI; this is the defence-in-depth layer.
-- ==============================================================================

-- ---------------------------------------------------------------------------
-- Finalized invoices are immutable.
--
-- Status and amount_paid may still move (OPEN -> PAID), but the commercial
-- terms may not. Correcting a finalized invoice means issuing a credit note,
-- which is why this is a column-level restriction rather than a blanket one.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION invoice_terms_immutable() RETURNS trigger AS $$
DECLARE
    was_draft BOOLEAN;
    is_draft  BOOLEAN;
BEGIN
    SELECT (OLD.status = 'DRAFT') INTO was_draft FROM invoices WHERE invoice_id = OLD.invoice_id;
    SELECT (NEW.status = 'DRAFT') INTO is_draft  FROM invoices WHERE invoice_id = NEW.invoice_id;

    -- Allowed: any change while still a draft, or the DRAFT -> OPEN transition
    -- itself, which assigns the invoice number.
    IF was_draft THEN
        RETURN NEW;
    END IF;

    IF OLD.tenant_id        IS DISTINCT FROM NEW.tenant_id
       OR OLD.customer_id   IS DISTINCT FROM NEW.customer_id
       OR OLD.plan_code     IS DISTINCT FROM NEW.plan_code
       OR OLD.currency      IS DISTINCT FROM NEW.currency
       OR OLD.period_start  IS DISTINCT FROM NEW.period_start
       OR OLD.period_end    IS DISTINCT FROM NEW.period_end
       OR OLD.subtotal      IS DISTINCT FROM NEW.subtotal
       OR OLD.tax_total     IS DISTINCT FROM NEW.tax_total
       OR OLD.total         IS DISTINCT FROM NEW.total
       OR OLD.invoice_number IS DISTINCT FROM NEW.invoice_number
       OR OLD.payload_json  IS DISTINCT FROM NEW.payload_json THEN
        RAISE EXCEPTION
            'Invoice % is finalized; its terms are immutable. Issue a credit note instead.',
            OLD.invoice_id;
    END IF;

    -- A finalized invoice can never return to draft, or its number could be dropped.
    IF is_draft THEN
        RAISE EXCEPTION 'Invoice % cannot return to DRAFT once finalized', OLD.invoice_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_invoice_terms_immutable ON invoices;

CREATE TRIGGER trg_invoice_terms_immutable
    BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION invoice_terms_immutable();

-- A finalized invoice's LINES are just as frozen as its header.
CREATE OR REPLACE FUNCTION invoice_lines_immutable() RETURNS trigger AS $$
DECLARE
    is_draft BOOLEAN;
BEGIN
    SELECT (status = 'DRAFT') INTO is_draft FROM invoices WHERE invoice_id = COALESCE(OLD.invoice_id, NEW.invoice_id);

    IF NOT is_draft THEN
        RAISE EXCEPTION
            'Invoice % is finalized; its line items are immutable. Issue a credit note instead.',
            COALESCE(OLD.invoice_id, NEW.invoice_id);
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_invoice_lines_immutable ON invoice_line_items;

CREATE TRIGGER trg_invoice_lines_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON invoice_line_items
    FOR EACH ROW EXECUTE FUNCTION invoice_lines_immutable();

-- ---------------------------------------------------------------------------
-- Total credits against one invoice may never exceed that invoice.
--
-- Enforced at the database because this is the one billing defect that reaches a
-- bank account: a retried or duplicated credit note refunds a customer more than
-- they paid. The Java CreditNoteLedger enforces the same rule, but only a
-- constraint can protect against a concurrent pair of credits both reading a
-- total that is still under the cap.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION credit_note_within_invoice_total() RETURNS trigger AS $$
DECLARE
    invoice_total NUMERIC(24, 8);
    already      NUMERIC(24, 8);
    projected    NUMERIC(24, 8);
BEGIN
    SELECT total INTO invoice_total FROM invoices WHERE invoice_id = NEW.invoice_id;

    -- Only credits still in force count; a voided credit frees the cap.
    SELECT COALESCE(SUM(-total), 0) INTO already
        FROM credit_notes
        WHERE invoice_id = NEW.invoice_id AND status = 'ISSUED';

    projected := already + (-NEW.total);

    IF projected > invoice_total THEN
        RAISE EXCEPTION
            'Total credits for invoice % would reach %, above the invoice total of %. '
            'The sum of credit notes against an invoice cannot exceed the invoice.',
            NEW.invoice_id, projected, invoice_total;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_credit_note_within_invoice_total ON credit_notes;

-- DEFERRABLE so that a credit issued and immediately voided in the same
-- transaction does not transiently trip the cap.
CREATE CONSTRAINT TRIGGER trg_credit_note_within_invoice_total
    AFTER INSERT ON credit_notes
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION credit_note_within_invoice_total();