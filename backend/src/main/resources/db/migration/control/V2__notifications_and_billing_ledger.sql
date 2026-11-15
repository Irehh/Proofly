-- Control-Plane Schema V2: Notification Outbox + Double-Entry Billing Ledger + Paystack Subscriptions

CREATE TABLE notification_outbox (
    id              UUID PRIMARY KEY,
    tenant_id       UUID REFERENCES tenants(id),
    channel         VARCHAR(16) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'WEBHOOK')),
    recipient       VARCHAR(255) NOT NULL,
    template_code   VARCHAR(64) NOT NULL,
    subject         VARCHAR(255),
    payload_jsonb   JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD_LETTER')),
    attempt_count   INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    correlation_id  VARCHAR(128) NOT NULL,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_outbox_due
    ON notification_outbox (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_notification_outbox_tenant
    ON notification_outbox (tenant_id, created_at DESC);

-- ============================================================================
-- Double-Entry Billing Ledger (BIGINT minor units + ISO-4217 currency)
-- ============================================================================

CREATE TABLE ledger_accounts (
    id              UUID PRIMARY KEY,
    code            VARCHAR(128) NOT NULL UNIQUE,
    owner_tenant_id UUID REFERENCES tenants(id),
    account_type    VARCHAR(24) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency        CHAR(3) NOT NULL,
    allow_negative  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    idempotency_key   VARCHAR(160) NOT NULL UNIQUE,
    entry_type        VARCHAR(32) NOT NULL CHECK (entry_type IN ('CHARGE', 'SETTLEMENT', 'REFUND', 'CREDIT_ADJ', 'REVERSAL')),
    reference         VARCHAR(160) NOT NULL,
    description       TEXT NOT NULL,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    reverses_entry_id UUID REFERENCES journal_entries(id),
    prev_hash         CHAR(64) NOT NULL,
    row_hash          CHAR(64) NOT NULL,
    created_by        VARCHAR(128) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ledger_lines (
    id           UUID PRIMARY KEY,
    entry_id     UUID NOT NULL REFERENCES journal_entries(id),
    account_id   UUID NOT NULL REFERENCES ledger_accounts(id),
    direction    VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_lines_entry ON ledger_lines (entry_id);
CREATE INDEX idx_ledger_lines_account_created ON ledger_lines (account_id, created_at, id);

CREATE TABLE account_balances (
    account_id    UUID PRIMARY KEY REFERENCES ledger_accounts(id),
    balance_minor BIGINT NOT NULL DEFAULT 0,
    currency      CHAR(3) NOT NULL,
    version       BIGINT NOT NULL DEFAULT 0,
    last_entry_id UUID REFERENCES journal_entries(id),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE balance_checkpoints (
    id                     UUID PRIMARY KEY,
    account_id             UUID NOT NULL REFERENCES ledger_accounts(id),
    as_of_entry_id         UUID NOT NULL REFERENCES journal_entries(id),
    verified_balance_minor BIGINT NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Append-only enforcement on journal_entries and ledger_lines
CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_modification();

CREATE TRIGGER trg_ledger_lines_immutable
    BEFORE UPDATE OR DELETE ON ledger_lines
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_modification();

-- Deferred constraint trigger enforcing double-entry balance and single currency per journal entry
CREATE OR REPLACE FUNCTION verify_journal_entry_balanced()
RETURNS TRIGGER AS $$
DECLARE
    debit_sum     BIGINT;
    credit_sum    BIGINT;
    line_count    INTEGER;
    currency_cnt  INTEGER;
BEGIN
    SELECT
        COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount_minor ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount_minor ELSE 0 END), 0),
        COUNT(*),
        COUNT(DISTINCT l.currency)
    INTO debit_sum, credit_sum, line_count, currency_cnt
    FROM ledger_lines l
    WHERE l.entry_id = NEW.entry_id;

    IF line_count < 2 THEN
        RAISE EXCEPTION 'Journal entry % must have at least 2 ledger lines', NEW.entry_id;
    END IF;

    IF currency_cnt <> 1 THEN
        RAISE EXCEPTION 'Journal entry % mixes multiple currencies', NEW.entry_id;
    END IF;

    IF debit_sum <> credit_sum THEN
        RAISE EXCEPTION 'Journal entry % is unbalanced: debits=% credits=%', NEW.entry_id, debit_sum, credit_sum;
    END IF;

    IF EXISTS (
        SELECT 1 FROM ledger_lines l
        JOIN ledger_accounts a ON a.id = l.account_id
        WHERE l.entry_id = NEW.entry_id AND l.currency <> a.currency
    ) THEN
        RAISE EXCEPTION 'Ledger line currency does not match ledger_account currency for entry %', NEW.entry_id;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entry_balanced
    AFTER INSERT ON ledger_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_journal_entry_balanced();

-- Seed core platform ledger accounts (NGN primary)
INSERT INTO ledger_accounts (id, code, owner_tenant_id, account_type, currency, allow_negative)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'platform:paystack_clearing',      NULL, 'ASSET',     'NGN', TRUE),
    ('10000000-0000-0000-0000-000000000002', 'platform:revenue:subscriptions',  NULL, 'REVENUE',   'NGN', FALSE),
    ('10000000-0000-0000-0000-000000000003', 'platform:tax_payable',            NULL, 'LIABILITY', 'NGN', FALSE),
    ('10000000-0000-0000-0000-000000000004', 'platform:refunds',                NULL, 'EXPENSE',   'NGN', TRUE);

INSERT INTO account_balances (account_id, balance_minor, currency)
SELECT id, 0, currency FROM ledger_accounts;

-- ============================================================================
-- Subscriptions, Payments, Webhook Events, Idempotency & Reconciliation
-- ============================================================================

CREATE TABLE subscriptions (
    id                         UUID PRIMARY KEY,
    tenant_id                  UUID NOT NULL REFERENCES tenants(id),
    plan_id                    UUID NOT NULL REFERENCES plans(id),
    billing_cycle              VARCHAR(16) NOT NULL CHECK (billing_cycle IN ('MONTHLY', 'ANNUAL')),
    status                     VARCHAR(24) NOT NULL CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'GRACE', 'SUSPENDED', 'CANCELED')),
    current_period_start       TIMESTAMPTZ NOT NULL,
    current_period_end         TIMESTAMPTZ NOT NULL,
    grace_until                TIMESTAMPTZ,
    cancel_at_period_end       BOOLEAN NOT NULL DEFAULT FALSE,
    paystack_subscription_code VARCHAR(128),
    version                    BIGINT NOT NULL DEFAULT 0,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_active_sub_per_tenant
    ON subscriptions (tenant_id)
    WHERE status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'GRACE');

CREATE TABLE payments (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenants(id),
    subscription_id    UUID REFERENCES subscriptions(id),
    target_plan_id     UUID NOT NULL REFERENCES plans(id),
    billing_cycle      VARCHAR(16) NOT NULL CHECK (billing_cycle IN ('MONTHLY', 'ANNUAL')),
    paystack_reference VARCHAR(128) NOT NULL UNIQUE,
    amount_minor       BIGINT NOT NULL CHECK (amount_minor >= 0),
    currency           CHAR(3) NOT NULL,
    status             VARCHAR(24) NOT NULL CHECK (status IN ('INITIATED', 'SUCCEEDED', 'FAILED', 'ABANDONED', 'REFUNDED', 'CHARGEBACK')),
    idempotency_key    VARCHAR(160) NOT NULL UNIQUE,
    initiated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at         TIMESTAMPTZ
);

CREATE TABLE payment_events (
    id                 UUID PRIMARY KEY,
    provider_event_id  VARCHAR(160) NOT NULL UNIQUE,
    paystack_reference VARCHAR(128),
    event_type         VARCHAR(64) NOT NULL,
    signature_valid    BOOLEAN NOT NULL,
    payload_jsonb      JSONB NOT NULL,
    status             VARCHAR(24) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    processed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE idempotency_keys (
    key             VARCHAR(160) PRIMARY KEY,
    scope           VARCHAR(64) NOT NULL,
    request_hash    CHAR(64) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body   JSONB NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE reconciliation_issues (
    id             UUID PRIMARY KEY,
    issue_type     VARCHAR(48) NOT NULL CHECK (issue_type IN ('BALANCE_MISMATCH', 'PAYSTACK_MISMATCH', 'UNSETTLED_WEBHOOK')),
    account_id     UUID REFERENCES ledger_accounts(id),
    payment_id     UUID REFERENCES payments(id),
    expected_minor BIGINT NOT NULL,
    actual_minor   BIGINT NOT NULL,
    currency       CHAR(3) NOT NULL,
    details_jsonb  JSONB NOT NULL DEFAULT '{}'::jsonb,
    status         VARCHAR(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    detected_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ
);
