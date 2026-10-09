-- ==============================================================================
-- Flyway Migration V5: database-level ledger immutability (PostgreSQL only)
--
-- V4 creates the append-only ledger table and its integrity constraints. This
-- migration closes the last hole: without it, the table is append-only only by
-- convention, and a bug or a hand-edited session could UPDATE or DELETE history.
--
-- WHY THIS IS A SEPARATE FILE
--
-- It uses PostgreSQL's CREATE RULE, which the H2 build used by the test suite does
-- not implement. V4 is portable and therefore fully exercised by the automated
-- tests; THIS script is verified only against a real PostgreSQL server. It is
-- excluded from the H2 test fixture on purpose rather than silently degraded -
-- see BaseJdbcRepositoryTest, which loads V1-V4 and V6 but not V5.
--
-- If you run the suite against H2 you are not testing these rules. The repository
-- implementations refuse to update or delete entries in Java, so the application
-- path is covered; only the defence-in-depth layer is unverified locally.
-- ==============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_rules
        WHERE schemaname = current_schema() AND tablename = 'wallet_ledger_entries'
    ) THEN

        -- Financial history is immutable. Corrections are REVERSAL rows, never an UPDATE.
        CREATE RULE wallet_ledger_no_update AS
            ON UPDATE TO wallet_ledger_entries
            DO INSTEAD NOTHING;

        CREATE RULE wallet_ledger_no_delete AS
            ON DELETE TO wallet_ledger_entries
            DO INSTEAD NOTHING;

    END IF;
END
$$;

-- An entry that has already been reversed must not be reversible a second time:
-- double-reversing would credit a customer money that was never drawn.
CREATE OR REPLACE FUNCTION wallet_ledger_single_reversal() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM wallet_ledger_entries
        WHERE entry_type = 'REVERSAL' AND reverses_entry_id = NEW.reverses_entry_id
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