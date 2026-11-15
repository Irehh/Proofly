-- Control-Plane Schema V1
-- Contains: tenants, global_user_directory, plans, platform_audit_log, shedlock, event_publication

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE plans (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(32) NOT NULL UNIQUE,
    name                VARCHAR(80) NOT NULL,
    max_members         INTEGER NOT NULL,
    max_sites           INTEGER NOT NULL,
    monthly_price_minor BIGINT NOT NULL,
    annual_price_minor  BIGINT NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'NGN',
    features_jsonb      JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO plans (id, code, name, max_members, max_sites, monthly_price_minor, annual_price_minor, currency, features_jsonb)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'FREE',       'Free',       4,    1,   0,          0,           'NGN', '{"csvExport": false, "mfaRequired": false}'::jsonb),
    ('00000000-0000-0000-0000-000000000002', 'STARTER',    'Starter',    20,   3,   2500000,    27000000,    'NGN', '{"csvExport": true,  "mfaRequired": false}'::jsonb),
    ('00000000-0000-0000-0000-000000000003', 'TEAM',       'Team',       75,   10,  7500000,    81000000,    'NGN', '{"csvExport": true,  "mfaRequired": true}'::jsonb),
    ('00000000-0000-0000-0000-000000000004', 'GROWTH',     'Growth',     250,  35,  19500000,   210600000,   'NGN', '{"csvExport": true,  "mfaRequired": true}'::jsonb),
    ('00000000-0000-0000-0000-000000000005', 'BUSINESS',   'Business',   1000, 150, 48000000,   518400000,   'NGN', '{"csvExport": true,  "mfaRequired": true}'::jsonb),
    ('00000000-0000-0000-0000-000000000006', 'ENTERPRISE', 'Enterprise', 10000,1000,120000000, 1296000000,  'NGN', '{"csvExport": true,  "mfaRequired": true, "dedicatedCluster": true}'::jsonb);

CREATE TABLE tenants (
    id                  UUID PRIMARY KEY,
    slug                VARCHAR(64) NOT NULL UNIQUE,
    name                VARCHAR(160) NOT NULL,
    status              VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'PROVISIONING', 'ACTIVE', 'SUSPENDED', 'DELETING')),
    db_host             VARCHAR(255) NOT NULL,
    db_port             INTEGER NOT NULL,
    db_name             VARCHAR(128) NOT NULL UNIQUE,
    db_secret_ref       VARCHAR(255) NOT NULL,
    schema_version      VARCHAR(32) NOT NULL DEFAULT '0',
    current_plan_id     UUID NOT NULL REFERENCES plans(id),
    default_currency    CHAR(3) NOT NULL DEFAULT 'NGN',
    provisioning_error  TEXT,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_status ON tenants (status);

CREATE TABLE global_user_directory (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    email               VARCHAR(255),
    phone_e164          VARCHAR(32),
    tenant_user_id      UUID NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_directory_identifier CHECK (email IS NOT NULL OR phone_e164 IS NOT NULL)
);

CREATE UNIQUE INDEX uq_global_user_email ON global_user_directory (LOWER(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_global_user_phone ON global_user_directory (phone_e164) WHERE phone_e164 IS NOT NULL;
CREATE INDEX idx_global_user_tenant ON global_user_directory (tenant_id);

CREATE TABLE platform_audit_log (
    id                  UUID PRIMARY KEY,
    actor_id            VARCHAR(128) NOT NULL,
    actor_type          VARCHAR(32) NOT NULL,
    tenant_id           UUID,
    action              VARCHAR(96) NOT NULL,
    entity_type         VARCHAR(64) NOT NULL,
    entity_id           VARCHAR(128) NOT NULL,
    before_state        JSONB,
    after_state         JSONB,
    request_id          VARCHAR(128) NOT NULL,
    ip_address          VARCHAR(64),
    prev_hash           CHAR(64) NOT NULL,
    row_hash            CHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_platform_audit_tenant_created ON platform_audit_log (tenant_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_immutable_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only and immutable', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_platform_audit_immutable
    BEFORE UPDATE OR DELETE ON platform_audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_modification();

CREATE TABLE shedlock (
    name        VARCHAR(64) PRIMARY KEY,
    lock_until  TIMESTAMPTZ NOT NULL,
    locked_at   TIMESTAMPTZ NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

CREATE TABLE event_publication (
    id               UUID PRIMARY KEY,
    listener_id      VARCHAR(512) NOT NULL,
    event_type       VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ
);

CREATE INDEX idx_event_pub_incomplete ON event_publication (completion_date) WHERE completion_date IS NULL;
