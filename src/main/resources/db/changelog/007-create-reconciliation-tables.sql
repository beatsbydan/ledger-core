--liquibase formatted sql

--changeset ledgercore:007-create-reconciliation-runs
CREATE TABLE reconciliation_runs (
    id                   BIGSERIAL PRIMARY KEY,
    run_date             DATE NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    ledger_rows_scanned  INT NOT NULL DEFAULT 0,
    bank_rows_scanned    INT NOT NULL DEFAULT 0,
    mismatch_count       INT NOT NULL DEFAULT 0,
    failure_reason       VARCHAR(500),
    started_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ,
    CONSTRAINT chk_reconciliation_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);
--rollback DROP TABLE reconciliation_runs;

--changeset ledgercore:007-create-reconciliation-mismatches
CREATE TABLE reconciliation_mismatches (
    id                        BIGSERIAL PRIMARY KEY,
    run_id                    BIGINT NOT NULL REFERENCES reconciliation_runs(id),
    mismatch_type             VARCHAR(20) NOT NULL,
    entry_uuid                VARCHAR(36),
    bank_reference            VARCHAR(36),
    account_number            VARCHAR(34) NOT NULL,
    ledger_amount_minor_units BIGINT,
    bank_amount_minor_units   BIGINT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_reconciliation_mismatches_type
        CHECK (mismatch_type IN ('LEDGER_ONLY', 'BANK_ONLY', 'AMOUNT_MISMATCH'))
);
--rollback DROP TABLE reconciliation_mismatches;

--changeset ledgercore:007-index-mismatches-run
CREATE INDEX idx_reconciliation_mismatches_run ON reconciliation_mismatches(run_id);
--rollback DROP INDEX idx_reconciliation_mismatches_run;
