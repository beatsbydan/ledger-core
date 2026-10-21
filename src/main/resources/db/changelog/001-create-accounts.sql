--liquibase formatted sql

--changeset ledgercore:001-create-accounts
CREATE TABLE accounts (
    id                  BIGSERIAL PRIMARY KEY,
    account_number      VARCHAR(34)  NOT NULL,
    owner_name          VARCHAR(255) NOT NULL,
    account_type        VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    currency            VARCHAR(3)   NOT NULL DEFAULT 'NGN',
    balance_minor_units BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_account_number UNIQUE (account_number),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance_minor_units >= 0),
    CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT chk_accounts_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM'))
);
--rollback DROP TABLE accounts;
