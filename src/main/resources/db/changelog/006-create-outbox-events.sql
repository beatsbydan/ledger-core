--liquibase formatted sql

--changeset ledgercore:006-create-outbox-events
CREATE TABLE outbox_events (
    id                 BIGSERIAL PRIMARY KEY,
    journal_entry_id   BIGINT NOT NULL REFERENCES journal_entries(id),
    event_type         VARCHAR(50) NOT NULL,
    payload            JSONB NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts           INT NOT NULL DEFAULT 0,
    kafka_topic        VARCHAR(100) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at       TIMESTAMPTZ,
    CONSTRAINT chk_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED'))
);
--rollback DROP TABLE outbox_events;

--changeset ledgercore:006-index-status-created
CREATE INDEX idx_outbox_events_status_created ON outbox_events(status, created_at);
--rollback DROP INDEX idx_outbox_events_status_created;
