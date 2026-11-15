-- Control-Plane Schema V3:
-- 1. Consolidate active plans to 4 cheaper tiers (FREE, STARTER, GROWTH, ENTERPRISE)
-- 2. Add multi-currency plan_prices table routed by payment gateway (PAYSTACK / STRIPE)
-- 3. Extend payments & payment_events with gateway + provider_session_id
-- 4. Harden notification_outbox stuck-PROCESSING recovery columns + reconciliation issue types

-- Migrate any legacy references on removed TEAM/BUSINESS tiers before deactivating them
UPDATE tenants
SET current_plan_id = '00000000-0000-0000-0000-000000000004'
WHERE current_plan_id = '00000000-0000-0000-0000-000000000003';

UPDATE tenants
SET current_plan_id = '00000000-0000-0000-0000-000000000006'
WHERE current_plan_id = '00000000-0000-0000-0000-000000000005';

UPDATE subscriptions
SET plan_id = '00000000-0000-0000-0000-000000000004'
WHERE plan_id = '00000000-0000-0000-0000-000000000003';

UPDATE subscriptions
SET plan_id = '00000000-0000-0000-0000-000000000006'
WHERE plan_id = '00000000-0000-0000-0000-000000000005';

UPDATE payments
SET target_plan_id = '00000000-0000-0000-0000-000000000004'
WHERE target_plan_id = '00000000-0000-0000-0000-000000000003';

UPDATE payments
SET target_plan_id = '00000000-0000-0000-0000-000000000006'
WHERE target_plan_id = '00000000-0000-0000-0000-000000000005';

-- Deactivate removed tiers (TEAM, BUSINESS)
UPDATE plans
SET is_active = FALSE
WHERE code IN ('TEAM', 'BUSINESS');

-- Update the 4 active plans to simpler, more affordable pricing (NGN kobo minor units, 10% off annual)
UPDATE plans
SET name = 'Free',
    max_members = 4,
    max_sites = 1,
    monthly_price_minor = 0,
    annual_price_minor = 0,
    features_jsonb = '{"csvExport": false, "mfaRequired": false, "leaveRouting": false}'::jsonb,
    is_active = TRUE
WHERE code = 'FREE';

UPDATE plans
SET name = 'Starter',
    max_members = 25,
    max_sites = 3,
    monthly_price_minor = 900000,
    annual_price_minor = 9720000,
    features_jsonb = '{"csvExport": true, "mfaRequired": false, "leaveRouting": true}'::jsonb,
    is_active = TRUE
WHERE code = 'STARTER';

UPDATE plans
SET name = 'Growth',
    max_members = 100,
    max_sites = 15,
    monthly_price_minor = 2900000,
    annual_price_minor = 31320000,
    features_jsonb = '{"csvExport": true, "mfaRequired": true, "leaveRouting": true}'::jsonb,
    is_active = TRUE
WHERE code = 'GROWTH';

UPDATE plans
SET name = 'Enterprise',
    max_members = 5000,
    max_sites = 500,
    monthly_price_minor = 9500000,
    annual_price_minor = 102600000,
    features_jsonb = '{"csvExport": true, "mfaRequired": true, "leaveRouting": true, "dedicatedCluster": true}'::jsonb,
    is_active = TRUE
WHERE code = 'ENTERPRISE';

-- Multi-currency plan catalog routed by payment gateway (all prices in integer minor units)
CREATE TABLE plan_prices (
    id                  UUID PRIMARY KEY,
    plan_id             UUID NOT NULL REFERENCES plans(id),
    currency            CHAR(3) NOT NULL,
    gateway             VARCHAR(24) NOT NULL CHECK (gateway IN ('PAYSTACK', 'STRIPE')),
    monthly_price_minor BIGINT NOT NULL CHECK (monthly_price_minor >= 0),
    annual_price_minor  BIGINT NOT NULL CHECK (annual_price_minor >= 0),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_plan_price_currency UNIQUE (plan_id, currency)
);

-- Seed exact 10%-annual-discount prices across NGN/KES/GHS (Paystack) and USD/GBP (Stripe)
INSERT INTO plan_prices (id, plan_id, currency, gateway, monthly_price_minor, annual_price_minor)
VALUES
    -- FREE
    ('20000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'NGN', 'PAYSTACK', 0,        0),
    ('20000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'USD', 'STRIPE',   0,        0),
    ('20000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', 'GBP', 'STRIPE',   0,        0),
    ('20000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', 'KES', 'PAYSTACK', 0,        0),
    ('20000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001', 'GHS', 'PAYSTACK', 0,        0),

    -- STARTER: ₦9,000 | $8 | £6 | KSh 900 | GH₵110
    ('20000000-0000-0000-0000-000000000011', '00000000-0000-0000-0000-000000000002', 'NGN', 'PAYSTACK', 900000,   9720000),
    ('20000000-0000-0000-0000-000000000012', '00000000-0000-0000-0000-000000000002', 'USD', 'STRIPE',   800,      8640),
    ('20000000-0000-0000-0000-000000000013', '00000000-0000-0000-0000-000000000002', 'GBP', 'STRIPE',   600,      6480),
    ('20000000-0000-0000-0000-000000000014', '00000000-0000-0000-0000-000000000002', 'KES', 'PAYSTACK', 90000,    972000),
    ('20000000-0000-0000-0000-000000000015', '00000000-0000-0000-0000-000000000002', 'GHS', 'PAYSTACK', 11000,    118800),

    -- GROWTH: ₦29,000 | $24 | £19 | KSh 2,900 | GH₵350
    ('20000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000004', 'NGN', 'PAYSTACK', 2900000,  31320000),
    ('20000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000004', 'USD', 'STRIPE',   2400,     25920),
    ('20000000-0000-0000-0000-000000000023', '00000000-0000-0000-0000-000000000004', 'GBP', 'STRIPE',   1900,     20520),
    ('20000000-0000-0000-0000-000000000024', '00000000-0000-0000-0000-000000000004', 'KES', 'PAYSTACK', 290000,   3132000),
    ('20000000-0000-0000-0000-000000000025', '00000000-0000-0000-0000-000000000004', 'GHS', 'PAYSTACK', 35000,    378000),

    -- ENTERPRISE: ₦95,000 | $79 | £62 | KSh 9,500 | GH₵1,150
    ('20000000-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000006', 'NGN', 'PAYSTACK', 9500000,  102600000),
    ('20000000-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000006', 'USD', 'STRIPE',   7900,     85320),
    ('20000000-0000-0000-0000-000000000033', '00000000-0000-0000-0000-000000000006', 'GBP', 'STRIPE',   6200,     66960),
    ('20000000-0000-0000-0000-000000000034', '00000000-0000-0000-0000-000000000006', 'KES', 'PAYSTACK', 950000,   10260000),
    ('20000000-0000-0000-0000-000000000035', '00000000-0000-0000-0000-000000000006', 'GHS', 'PAYSTACK', 115000,   1242000);

-- Gateway metadata on payments & payment_events
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS gateway VARCHAR(24) NOT NULL DEFAULT 'PAYSTACK'
        CHECK (gateway IN ('PAYSTACK', 'STRIPE')),
    ADD COLUMN IF NOT EXISTS provider_session_id VARCHAR(160);

CREATE INDEX IF NOT EXISTS idx_payments_pending_sweep
    ON payments (status, initiated_at)
    WHERE status = 'INITIATED';

ALTER TABLE payment_events
    ADD COLUMN IF NOT EXISTS gateway VARCHAR(24) NOT NULL DEFAULT 'PAYSTACK'
        CHECK (gateway IN ('PAYSTACK', 'STRIPE'));

-- Outbox stuck-'PROCESSING' watchdog support
ALTER TABLE notification_outbox
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_notification_outbox_stuck_processing
    ON notification_outbox (processing_started_at)
    WHERE status = 'PROCESSING';

-- Expand reconciliation_issues allowed issue types + prevent duplicate OPEN balance alerts per account
ALTER TABLE reconciliation_issues
    DROP CONSTRAINT IF EXISTS reconciliation_issues_issue_type_check;

ALTER TABLE reconciliation_issues
    ADD CONSTRAINT reconciliation_issues_issue_type_check
        CHECK (issue_type IN (
            'BALANCE_MISMATCH',
            'PAYSTACK_MISMATCH',
            'GATEWAY_MISMATCH',
            'UNSETTLED_WEBHOOK',
            'MISSING_WEBHOOK',
            'STUCK_PROCESSING'
        ));

CREATE UNIQUE INDEX IF NOT EXISTS uq_open_reconciliation_issue_account
    ON reconciliation_issues (issue_type, account_id)
    WHERE status = 'OPEN' AND account_id IS NOT NULL;
