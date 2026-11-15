-- Tenant Schema V2: Sites (PostGIS Geofences), Multi-Site Assignments, Attendance, Corrections & Leave

CREATE TABLE sites (
    id                          UUID PRIMARY KEY,
    name                        VARCHAR(160) NOT NULL,
    code                        VARCHAR(64) NOT NULL UNIQUE,
    address                     VARCHAR(255),
    latitude                    DOUBLE PRECISION NOT NULL,
    longitude                   DOUBLE PRECISION NOT NULL,
    center_point                GEOGRAPHY(Point, 4326) NOT NULL,
    radius_meters               INTEGER NOT NULL CHECK (radius_meters BETWEEN 25 AND 20000),
    timezone                    VARCHAR(64) NOT NULL DEFAULT 'Africa/Lagos',
    shift_start_local           TIME NOT NULL DEFAULT '09:00:00',
    late_grace_minutes          INTEGER NOT NULL DEFAULT 10 CHECK (late_grace_minutes >= 0 AND late_grace_minutes <= 240),
    overtime_threshold_minutes  INTEGER NOT NULL DEFAULT 480 CHECK (overtime_threshold_minutes >= 60 AND overtime_threshold_minutes <= 1440),
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sites_center_gist ON sites USING GIST (center_point);
CREATE INDEX idx_sites_active ON sites (is_active, name);

CREATE TABLE site_assignments (
    id                          UUID PRIMARY KEY,
    membership_id               UUID NOT NULL REFERENCES memberships(id),
    site_id                     UUID NOT NULL REFERENCES sites(id),
    is_primary                  BOOLEAN NOT NULL DEFAULT FALSE,
    shift_start_override        TIME,
    late_grace_override         INTEGER CHECK (late_grace_override IS NULL OR (late_grace_override >= 0 AND late_grace_override <= 240)),
    overtime_threshold_override INTEGER CHECK (overtime_threshold_override IS NULL OR (overtime_threshold_override >= 60 AND overtime_threshold_override <= 1440)),
    assigned_by_membership_id   UUID REFERENCES memberships(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (membership_id, site_id)
);

CREATE INDEX idx_site_assignments_member ON site_assignments (membership_id);
CREATE INDEX idx_site_assignments_site ON site_assignments (site_id);

CREATE TABLE attendance_events (
    id                          UUID PRIMARY KEY,
    membership_id               UUID NOT NULL REFERENCES memberships(id),
    site_id                     UUID NOT NULL REFERENCES sites(id),
    event_type                  VARCHAR(16) NOT NULL CHECK (event_type IN ('CLOCK_IN', 'CLOCK_OUT')),
    paired_clock_in_id          UUID REFERENCES attendance_events(id),
    occurred_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    local_date                  DATE NOT NULL,
    recorded_lat                DOUBLE PRECISION NOT NULL,
    recorded_lng                DOUBLE PRECISION NOT NULL,
    accuracy_meters             INTEGER NOT NULL CHECK (accuracy_meters >= 0),
    distance_meters             INTEGER NOT NULL CHECK (distance_meters >= 0),
    geofence_status             VARCHAR(24) NOT NULL CHECK (geofence_status IN ('VERIFIED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    is_late                     BOOLEAN NOT NULL DEFAULT FALSE,
    late_minutes                INTEGER NOT NULL DEFAULT 0 CHECK (late_minutes >= 0),
    worked_minutes              INTEGER NOT NULL DEFAULT 0 CHECK (worked_minutes >= 0),
    overtime_minutes            INTEGER NOT NULL DEFAULT 0 CHECK (overtime_minutes >= 0),
    notes                       VARCHAR(500),
    device_id                   UUID,
    reviewed_by_membership_id   UUID REFERENCES memberships(id),
    reviewed_at                 TIMESTAMPTZ,
    review_reason               VARCHAR(500),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attendance_member_date ON attendance_events (membership_id, local_date DESC, effective_at DESC);
CREATE INDEX idx_attendance_site_date ON attendance_events (site_id, local_date DESC);
CREATE INDEX idx_attendance_flagged ON attendance_events (geofence_status, occurred_at DESC)
    WHERE geofence_status = 'PENDING_REVIEW';

CREATE TABLE attendance_corrections (
    id                          UUID PRIMARY KEY,
    attendance_event_id         UUID NOT NULL REFERENCES attendance_events(id),
    membership_id               UUID NOT NULL REFERENCES memberships(id),
    requested_by_membership_id  UUID NOT NULL REFERENCES memberships(id),
    status                      VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    original_effective_at       TIMESTAMPTZ NOT NULL,
    proposed_effective_at       TIMESTAMPTZ NOT NULL,
    reason                      VARCHAR(500) NOT NULL,
    reviewed_by_membership_id   UUID REFERENCES memberships(id),
    review_note                 VARCHAR(500),
    reviewed_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_corrections_status ON attendance_corrections (status, created_at DESC);
CREATE INDEX idx_corrections_member ON attendance_corrections (membership_id, created_at DESC);

CREATE TABLE leave_requests (
    id                          UUID PRIMARY KEY,
    membership_id               UUID NOT NULL REFERENCES memberships(id),
    manager_membership_id       UUID REFERENCES memberships(id),
    leave_type                  VARCHAR(24) NOT NULL CHECK (leave_type IN ('ANNUAL', 'SICK', 'UNPAID', 'OTHER')),
    start_date                  DATE NOT NULL,
    end_date                    DATE NOT NULL,
    reason                      VARCHAR(500) NOT NULL,
    status                      VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELED')),
    reviewed_by_membership_id   UUID REFERENCES memberships(id),
    review_note                 VARCHAR(500),
    reviewed_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_leave_dates CHECK (end_date >= start_date)
);

CREATE INDEX idx_leave_member ON leave_requests (membership_id, start_date DESC);
CREATE INDEX idx_leave_manager_status ON leave_requests (manager_membership_id, status, created_at DESC);
