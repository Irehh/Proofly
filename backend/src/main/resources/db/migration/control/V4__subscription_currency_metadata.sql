-- Control-Plane Schema V4:
-- Persist exact settled currency & gateway on subscriptions so multi-currency payments
-- (e.g. USD via Stripe vs NGN via Paystack) never undergo implicit FX conversion.

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS currency CHAR(3) NOT NULL DEFAULT 'NGN',
    ADD COLUMN IF NOT EXISTS gateway VARCHAR(24) NOT NULL DEFAULT 'PAYSTACK'
        CHECK (gateway IN ('PAYSTACK', 'STRIPE'));

UPDATE subscriptions s
SET currency = COALESCE(
        (SELECT p.currency
         FROM payments p
         WHERE p.tenant_id = s.tenant_id AND p.status = 'SUCCEEDED'
         ORDER BY p.settled_at DESC NULLS LAST, p.initiated_at DESC
         LIMIT 1),
        (SELECT t.default_currency FROM tenants t WHERE t.id = s.tenant_id),
        'NGN'
    ),
    gateway = COALESCE(
        (SELECT p.gateway
         FROM payments p
         WHERE p.tenant_id = s.tenant_id AND p.status = 'SUCCEEDED'
         ORDER BY p.settled_at DESC NULLS LAST, p.initiated_at DESC
         LIMIT 1),
        'PAYSTACK'
    );
