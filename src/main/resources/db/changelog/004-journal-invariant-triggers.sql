--liquibase formatted sql

--changeset ledgercore:004-zero-sum-trigger splitStatements:false
-- A row-level CHECK cannot see sibling rows, so it cannot express "this entry's
-- lines sum to zero". A deferred constraint trigger can: it fires once per
-- inserted row but only evaluates at COMMIT (or SET CONSTRAINTS ... IMMEDIATE),
-- by which point every line for the entry is visible in the same transaction.
CREATE OR REPLACE FUNCTION check_journal_entry_balanced() RETURNS trigger AS $BODY$
DECLARE
    v_sum BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor_units
                              ELSE -amount_minor_units END), 0)
    INTO v_sum
    FROM journal_lines
    WHERE journal_entry_id = NEW.journal_entry_id;

    IF v_sum <> 0 THEN
        RAISE EXCEPTION 'journal_entry % is unbalanced (sum=%)', NEW.journal_entry_id, v_sum
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$BODY$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_lines_balanced
    AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();
--rollback DROP TRIGGER IF EXISTS trg_journal_lines_balanced ON journal_lines;
--rollback DROP FUNCTION IF EXISTS check_journal_entry_balanced();

--changeset ledgercore:004-immutability-triggers splitStatements:false
-- Journal rows are never edited or deleted - corrections only happen by
-- writing a new reversing entry. This trigger is the unbypassable backstop
-- behind the app-level rule, independent of which code path writes the row.
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS trigger AS $BODY$
BEGIN
    RAISE EXCEPTION '% on % is not permitted; journal records are immutable', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '23514';
END;
$BODY$ LANGUAGE plpgsql;

CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER trg_journal_lines_immutable
    BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
--rollback DROP TRIGGER IF EXISTS trg_journal_entries_immutable ON journal_entries;
--rollback DROP TRIGGER IF EXISTS trg_journal_lines_immutable ON journal_lines;
--rollback DROP FUNCTION IF EXISTS reject_mutation();
