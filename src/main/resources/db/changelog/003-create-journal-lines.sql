--liquibase formatted sql

--changeset ledgercore:003-create-journal-lines
CREATE TABLE journal_lines (
    id                  BIGSERIAL PRIMARY KEY,
    journal_entry_id    BIGINT NOT NULL REFERENCES journal_entries(id),
    account_id          BIGINT NOT NULL REFERENCES accounts(id),
    direction           VARCHAR(6) NOT NULL,
    amount_minor_units  BIGINT NOT NULL,
    line_number         SMALLINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_journal_lines_entry_line UNIQUE (journal_entry_id, line_number),
    CONSTRAINT chk_journal_lines_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_journal_lines_amount_positive CHECK (amount_minor_units > 0)
);
--rollback DROP TABLE journal_lines;

--changeset ledgercore:003-index-account-id
CREATE INDEX idx_journal_lines_account_id ON journal_lines(account_id, id);
--rollback DROP INDEX idx_journal_lines_account_id;

--changeset ledgercore:003-index-entry-id
CREATE INDEX idx_journal_lines_entry_id ON journal_lines(journal_entry_id);
--rollback DROP INDEX idx_journal_lines_entry_id;
