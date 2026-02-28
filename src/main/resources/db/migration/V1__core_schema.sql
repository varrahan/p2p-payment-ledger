-- ============================================================
-- V1: Core Schema — Users, Wallets, Ledger, Transfers
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---- Users ----
CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email      VARCHAR(255) UNIQUE NOT NULL,
    password   VARCHAR(255)        NOT NULL,  -- BCrypt hash
    full_name  VARCHAR(255)        NOT NULL,
    created_at TIMESTAMPTZ         NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ         NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);

-- ---- Wallets ----
CREATE TABLE wallets (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL REFERENCES users (id),
    currency        CHAR(3)     NOT NULL DEFAULT 'USD',  -- ISO 4217
    current_balance NUMERIC(19, 4) NOT NULL DEFAULT 0
        CONSTRAINT wallets_non_negative_balance CHECK (current_balance >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallets_user_id ON wallets (user_id);

-- ---- Transfers ----
CREATE TABLE transfers (
    id                 UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_wallet_id   UUID           NOT NULL REFERENCES wallets (id),
    receiver_wallet_id UUID           NOT NULL REFERENCES wallets (id),
    amount             NUMERIC(19, 4) NOT NULL
        CONSTRAINT transfers_positive_amount CHECK (amount > 0),
    currency           CHAR(3)        NOT NULL,
    status             VARCHAR(12)    NOT NULL DEFAULT 'PENDING'
        CONSTRAINT transfers_valid_status CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED','REVERSED')),
    idempotency_key    VARCHAR(255)   UNIQUE NOT NULL,
    description        VARCHAR(500),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_transfers_sender_wallet   ON transfers (sender_wallet_id);
CREATE INDEX idx_transfers_receiver_wallet ON transfers (receiver_wallet_id);
CREATE INDEX idx_transfers_status          ON transfers (status);
CREATE INDEX idx_transfers_idempotency     ON transfers (idempotency_key);

-- ---- Ledger Entries (immutable, append-only) ----
CREATE TABLE ledger_entries (
    id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID           NOT NULL REFERENCES transfers (id),
    wallet_id   UUID           NOT NULL REFERENCES wallets (id),
    entry_type  VARCHAR(6)     NOT NULL
        CONSTRAINT ledger_valid_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount      NUMERIC(19, 4) NOT NULL
        CONSTRAINT ledger_positive_amount CHECK (amount > 0),
    currency    CHAR(3)        NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_transfer_id ON ledger_entries (transfer_id);
CREATE INDEX idx_ledger_wallet_id   ON ledger_entries (wallet_id);
CREATE INDEX idx_ledger_entry_type  ON ledger_entries (entry_type);

-- ---- Idempotency Keys (durable, committed with the transfer) ----
CREATE TABLE idempotency_keys (
    key        VARCHAR(255) PRIMARY KEY,
    status     VARCHAR(12)  NOT NULL DEFAULT 'PROCESSING'
        CONSTRAINT idempotency_valid_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    response   JSONB,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);

-- ---- Transactional Outbox ----
CREATE TABLE outbox_events (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    payload    JSONB        NOT NULL,
    published  BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published, created_at) WHERE published = false;

-- ---- updated_at auto-update trigger ----
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at    BEFORE UPDATE ON users    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_wallets_updated_at  BEFORE UPDATE ON wallets  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_transfers_updated_at BEFORE UPDATE ON transfers FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
