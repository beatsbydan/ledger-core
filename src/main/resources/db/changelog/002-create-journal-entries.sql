--liquibase formatted sql

--changeset ledgercore:002-create-journal-entries
CREATE TABLE journal_entries (
    id                 BIGSERIAL PRIMARY KEY,
    entry_uuid         UUID NOT NULL DEFAULT gen_random_uuid(),
    entry_type         VARCHAR(20) NOT NULL DEFAULT 'TRANSFER',
    reversed_entry_id  BIGINT NULL REFERENCES journal_entries(id),
    description        VARCHAR(500),
    currency           VARCHAR(3) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(100),
    CONSTRAINT uq_journal_entries_uuid UNIQUE (entry_uuid),
    CONSTRAINT chk_journal_entries_type CHECK (entry_type IN ('TRANSFER', 'REVERSAL', 'ADJUSTMENT'))
);
--rollback DROP TABLE journal_entries;

--changeset ledgercore:002-index-reversed-entry
CREATE INDEX idx_journal_entries_reversed_entry ON journal_entries(reversed_entry_id);
--rollback DROP INDEX idx_journal_entries_reversed_entry;
