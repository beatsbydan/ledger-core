--liquibase formatted sql

--changeset ledgercore:005-create-idempotency-keys
CREATE TABLE idempotency_keys (
    id                   BIGSERIAL PRIMARY KEY,
    idempotency_key      VARCHAR(255) NOT NULL,
    request_fingerprint  VARCHAR(64)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    response_status      INT,
    response_body        JSONB,
    journal_entry_id     BIGINT NULL REFERENCES journal_entries(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);
--rollback DROP TABLE idempotency_keys;
