-- ==============================================================================
-- Flyway Migration V6: invoices and credit notes
--
-- The engine could compute an amount but had nowhere to put the document. This
-- creates the financial documents themselves, with the constraints that make
-- them defensible: numbered per tenant, terms frozen once finalized, and credit
-- notes that reference an original rather than mutating it.
--
-- Portable SQL only. PostgreSQL-specific immutability triggers live in V7,
-- because the H2 build used by the test suite cannot execute them.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Invoices
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS invoices (
    invoice_id        VARCHAR(128) PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    customer_id       VARCHAR(64)  NOT NULL,
    plan_code         VARCHAR(64)  NOT NULL,
    currency          VARCHAR(16)  NOT NULL,

    status            VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    invoice_number    VARCHAR(128),

    period_start      TIMESTAMP WITH TIME ZONE NOT NULL,
    period_end        TIMESTAMP WITH TIME ZONE NOT NULL,
    issued_at         TIMESTAMP WITH TIME ZONE NOT NULL,

    subtotal          NUMERIC(24, 8) NOT NULL,
    tax_total         NUMERIC(24, 8) NOT NULL,
    total             NUMERIC(24, 8) NOT NULL,
    amount_paid       NUMERIC(24, 8) NOT NULL DEFAULT 0,

    payload_json      TEXT         NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Only these states exist; an unknown value would be summed as if it were OPEN.
    CONSTRAINT ck_invoice_status
        CHECK (status IN ('DRAFT', 'OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE')),

    -- A draft has no number; a finalized invoice always does. This is what makes
    -- "the document number is assigned at finalization" a database fact.
    CONSTRAINT ck_invoice_number_required
        CHECK ((status = 'DRAFT' AND invoice_number IS NULL)
            OR (status <> 'DRAFT' AND invoice_number IS NOT NULL AND LENGTH(TRIM(invoice_number)) > 0)),

    -- total must be the sum of its parts, or the document contradicts itself.
    CONSTRAINT ck_invoice_totals CHECK (total = subtotal + tax_total),

    -- You cannot collect more than was owed.
    CONSTRAINT ck_invoice_amount_paid CHECK (amount_paid >= 0 AND amount_paid <= total),

    CONSTRAINT ck_invoice_period CHECK (period_end > period_start),

    -- An invoice number identifies a document to the customer and to tax authorities, so two
    -- invoices for one tenant may never share it.
    --
    -- A plain UNIQUE constraint rather than a partial index, because partial indexes are not
    -- portable to the H2 build used by the test suite. SQL treats NULLs as distinct in a UNIQUE
    -- constraint, so any number of un-numbered drafts for the same tenant remain legal - which is
    -- exactly the intent, since a draft has no number until finalization.
    CONSTRAINT uq_invoice_number UNIQUE (tenant_id, invoice_number)
);

-- Tenant-scoped listing, newest first. Also serves the cursor pagination.
CREATE INDEX IF NOT EXISTS idx_invoices_tenant_issued
    ON invoices (tenant_id, issued_at DESC, invoice_id DESC);

CREATE INDEX IF NOT EXISTS idx_invoices_customer_issued
    ON invoices (tenant_id, customer_id, issued_at DESC);

-- ------------------------------------------------------------------------------
-- 2. Invoice lines
--
-- Stored as rows rather than only inside payload_json so that a tax authority
-- asking "what was the rate, base and amount on line 3" is answerable without
-- parsing JSON, which is what e-invoicing regimes (DE B2B, FR Factur-X, IT SdI,
-- IN GST, SA ZATCA) require.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS invoice_line_items (
    invoice_id    VARCHAR(128) NOT NULL,
    line_index    INTEGER      NOT NULL,
    item_code     VARCHAR(128) NOT NULL,
    description   VARCHAR(512) NOT NULL,
    quantity      NUMERIC(24, 8) NOT NULL,
    unit_price    NUMERIC(24, 8) NOT NULL,
    amount        NUMERIC(24, 8) NOT NULL,
    tax_code      VARCHAR(64)  NOT NULL DEFAULT '',
    currency      VARCHAR(16)  NOT NULL,
    metadata_json TEXT         NOT NULL DEFAULT '{}',

    CONSTRAINT pk_invoice_line_items PRIMARY KEY (invoice_id, line_index),
    CONSTRAINT fk_invoice_line_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (invoice_id) ON DELETE RESTRICT,
    CONSTRAINT ck_invoice_line_index CHECK (line_index >= 0)
);

CREATE INDEX IF NOT EXISTS idx_invoice_lines_item_code
    ON invoice_line_items (item_code, invoice_id);

-- ------------------------------------------------------------------------------
-- 3. Credit notes
--
-- A separate document referencing an original invoice. NOT a negative invoice:
-- the original number must survive on statements and in closed-period filings.
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS credit_notes (
    credit_note_id VARCHAR(128) PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    invoice_id     VARCHAR(128) NOT NULL,
    invoice_number VARCHAR(128) NOT NULL,
    currency       VARCHAR(16)  NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'ISSUED',
    disposition    VARCHAR(32)  NOT NULL,
    total          NUMERIC(24, 8) NOT NULL,
    reason         VARCHAR(512) NOT NULL,
    issued_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    voided_at      TIMESTAMP WITH TIME ZONE,
    payload_json   TEXT         NOT NULL,

    CONSTRAINT fk_credit_note_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (invoice_id) ON DELETE RESTRICT,

    CONSTRAINT ck_credit_note_status
        CHECK (status IN ('ISSUED', 'VOID')),

    CONSTRAINT ck_credit_note_disposition
        CHECK (disposition IN ('REFUND', 'CREDIT_BALANCE', 'REPLACE')),

    -- A credit reduces an amount. A positive "credit" is a charge wearing the
    -- wrong label, and would make total credits meaningless.
    CONSTRAINT ck_credit_note_total_sign CHECK (total <= 0),

    -- A credit without a cause is not auditable.
    CONSTRAINT ck_credit_note_reason CHECK (LENGTH(TRIM(reason)) > 0),

    CONSTRAINT ck_credit_note_voided CHECK ((status = 'VOID') = (voided_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_credit_notes_invoice
    ON credit_notes (invoice_id, status);

CREATE TABLE IF NOT EXISTS credit_note_lines (
    credit_note_id VARCHAR(128) NOT NULL,
    line_index     INTEGER      NOT NULL,
    item_code      VARCHAR(128) NOT NULL,
    description    VARCHAR(512) NOT NULL,
    quantity       NUMERIC(24, 8) NOT NULL,
    unit_price     NUMERIC(24, 8) NOT NULL,
    amount         NUMERIC(24, 8) NOT NULL,
    tax_code       VARCHAR(64)  NOT NULL DEFAULT '',
    currency       VARCHAR(16)  NOT NULL,
    metadata_json  TEXT         NOT NULL DEFAULT '{}',

    CONSTRAINT pk_credit_note_lines PRIMARY KEY (credit_note_id, line_index),
    CONSTRAINT fk_credit_note_line FOREIGN KEY (credit_note_id)
        REFERENCES credit_notes (credit_note_id) ON DELETE RESTRICT
);