-- Tenant Schema V1 (Provisioned per Organization DB)

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE organization_settings (
    id                          UUID PRIMARY KEY,
    org_name                    VARCHAR(160) NOT NULL,
    default_timezone            VARCHAR(64) NOT NULL DEFAULT 'Africa/Lagos',
    late_grace_minutes          INTEGER NOT NULL DEFAULT 10,
    overtime_threshold_minutes  INTEGER NOT NULL DEFAULT 480,
    max_gps_accuracy_meters     INTEGER NOT NULL DEFAULT 100,
    allow_offline_clock         BOOLEAN NOT NULL DEFAULT TRUE,
    location_retention_days     INTEGER NOT NULL DEFAULT 365,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id                      UUID PRIMARY KEY,
    email                   VARCHAR(255),
    phone_e164              VARCHAR(32),
    full_name               VARCHAR(160) NOT NULL,
    password_hash           TEXT,
    pin_hash                TEXT,
    pin_failed_attempts     INTEGER NOT NULL DEFAULT 0,
    pin_locked_until        TIMESTAMPTZ,
    mfa_enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    mfa_secret_encrypted    BYTEA,
    status                  VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INVITED', 'LOCKED', 'DISABLED')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_user_contact CHECK (email IS NOT NULL OR phone_e164 IS NOT NULL)
);

CREATE UNIQUE INDEX uq_tenant_users_email ON users (LOWER(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_tenant_users_phone ON users (phone_e164) WHERE phone_e164 IS NOT NULL;

CREATE TABLE memberships (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL UNIQUE REFERENCES users(id),
    role                    VARCHAR(24) NOT NULL CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'EMPLOYEE')),
    manager_membership_id   UUID REFERENCES memberships(id),
    job_title               VARCHAR(120),
    employee_code           VARCHAR(64),
    status                  VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'OFFBOARDED')),
    joined_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_memberships_manager ON memberships (manager_membership_id);

CREATE TABLE invitations (
    id                       UUID PRIMARY KEY,
    invite_type              VARCHAR(16) NOT NULL CHECK (invite_type IN ('EMAIL', 'PHONE_PIN')),
    email                    VARCHAR(255),
    phone_e164               VARCHAR(32),
    role                     VARCHAR(24) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'EMPLOYEE')),
    manager_membership_id    UUID REFERENCES memberships(id),
    token_hash               CHAR(64) NOT NULL UNIQUE,
    expires_at               TIMESTAMPTZ NOT NULL,
    accepted_at              TIMESTAMPTZ,
    invited_by_membership_id UUID NOT NULL REFERENCES memberships(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE devices (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id),
    device_fingerprint  VARCHAR(128) NOT NULL,
    device_name         VARCHAR(120) NOT NULL,
    platform            VARCHAR(24) NOT NULL CHECK (platform IN ('WEB_PWA', 'IOS', 'ANDROID', 'API')),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, device_fingerprint)
);

CREATE TABLE refresh_token_families (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id),
    device_id           UUID NOT NULL REFERENCES devices(id),
    family_id           UUID NOT NULL UNIQUE,
    current_token_hash  CHAR(64) NOT NULL,
    parent_token_hash   CHAR(64),
    reuse_detected_at   TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_families_current ON refresh_token_families (current_token_hash);
CREATE INDEX idx_refresh_families_parent  ON refresh_token_families (parent_token_hash);

CREATE TABLE tenant_audit_log (
    id                  UUID PRIMARY KEY,
    actor_membership_id UUID,
    action              VARCHAR(96) NOT NULL,
    entity_type         VARCHAR(64) NOT NULL,
    entity_id           VARCHAR(128) NOT NULL,
    before_state        JSONB,
    after_state         JSONB,
    request_id          VARCHAR(128) NOT NULL,
    ip_address          VARCHAR(64),
    device_id           UUID,
    prev_hash           CHAR(64) NOT NULL,
    row_hash            CHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_audit_created ON tenant_audit_log (created_at DESC);

CREATE OR REPLACE FUNCTION reject_tenant_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only and immutable', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tenant_audit_immutable
    BEFORE UPDATE OR DELETE ON tenant_audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_tenant_audit_modification();

CREATE TABLE event_publication (
    id               UUID PRIMARY KEY,
    listener_id      VARCHAR(512) NOT NULL,
    event_type       VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    completion_date  TIMESTAMPTZ
);
