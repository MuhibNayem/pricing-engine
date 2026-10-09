-- ==============================================================================
-- Flyway Migration V5: database-level ledger immutability (PostgreSQL only)
--
-- V4 creates the append-only ledger table and its integrity constraints. This
-- migration closes the last hole: without it, the table is append-only only by
-- convention, and a bug or a hand-edited session could UPDATE or DELETE history.
--
-- WHY THIS IS A SEPARATE FILE
--
-- It uses plpgsql triggers, which the H2 build used by the test suite does not
-- implement. V4 is portable and therefore fully exercised by the automated
-- tests; THIS script is verified against real PostgreSQL by PostgresMigrationTest.
--
-- UPDATE and DELETE raise rather than being silently ignored: a rule that does
-- nothing makes a mutation look like it succeeded, which is worse for an audit
-- than an outright refusal.
-- ==============================================================================

-- An earlier revision of this file created DO INSTEAD NOTHING rules. They are removed rather
-- than left in place, because silently discarding a write is not the guarantee promised here.
DROP RULE IF EXISTS wallet_ledger_no_update ON wallet_ledger_entries;
DROP RULE IF EXISTS wallet_ledger_no_delete ON wallet_ledger_entries;

CREATE OR REPLACE FUNCTION wallet_ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'wallet_ledger_entries is append-only: % is rejected; corrections are REVERSAL rows',
        TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_wallet_ledger_no_update ON wallet_ledger_entries;
CREATE TRIGGER trg_wallet_ledger_no_update
    BEFORE UPDATE ON wallet_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION wallet_ledger_reject_mutation();

DROP TRIGGER IF EXISTS trg_wallet_ledger_no_delete ON wallet_ledger_entries;
CREATE TRIGGER trg_wallet_ledger_no_delete
    BEFORE DELETE ON wallet_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION wallet_ledger_reject_mutation();

-- An entry that has already been reversed must not be reversible a second time:
-- double-reversing would credit a customer money that was never drawn.
--
-- Excluding NEW.entry_id is the whole point: this is an AFTER INSERT trigger, so
-- without the exclusion the row being inserted matches its own EXISTS and EVERY
-- first reversal is rejected. The equivalent trigger in V11 gets this right.
CREATE OR REPLACE FUNCTION wallet_ledger_single_reversal() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM wallet_ledger_entries
        WHERE entry_type = 'REVERSAL'
          AND reverses_entry_id = NEW.reverses_entry_id
          AND entry_id <> NEW.entry_id
    ) THEN
        RAISE EXCEPTION
            'Ledger entry % has already been reversed; a second reversal would credit '
            'money that was never drawn', NEW.reverses_entry_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_wallet_ledger_single_reversal ON wallet_ledger_entries;

CREATE CONSTRAINT TRIGGER trg_wallet_ledger_single_reversal
    AFTER INSERT ON wallet_ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION wallet_ledger_single_reversal();
