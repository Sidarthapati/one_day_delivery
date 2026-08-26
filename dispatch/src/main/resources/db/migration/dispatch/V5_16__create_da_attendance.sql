-- M5 geocoded DA attendance: one positive present/absent record per DA per day. A DA is auto-marked
-- PRESENT when a GPS fix lands within the hub geofence (or they tap "I've arrived"); a station manager
-- can override to PRESENT/ABSENT after the shift cutoff. Distinct from da_status (live snapshot) — this
-- is the per-day attendance ledger. Enum-like columns are VARCHAR + app-side @Enumerated(STRING).
CREATE TABLE da_attendance (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id               UUID NOT NULL,
    city_id             UUID NOT NULL,
    attendance_date     DATE NOT NULL,
    shift_type          VARCHAR(20),                       -- SHIFT_1 | SHIFT_2 (from da_status)
    status              VARCHAR(16) NOT NULL,              -- PRESENT | ABSENT
    method              VARCHAR(24) NOT NULL,              -- AUTO_GEOFENCE | MANUAL_CHECKIN | MANAGER_PRESENT | MANAGER_ABSENT
    detected_lat        DOUBLE PRECISION,
    detected_lon        DOUBLE PRECISION,
    distance_m          DOUBLE PRECISION,                  -- metres from the hub at detection (null for manager overrides)
    marked_by_user_id   UUID,                              -- the station manager for MANAGER_* overrides; null for auto/self
    source_ping_at      TIMESTAMPTZ,                       -- device fix time the presence was derived from
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_da_attendance_da_date UNIQUE (da_id, attendance_date)  -- one shift/DA/day
);

-- Muster read: a city's attendance for a shift/date.
CREATE INDEX idx_da_attendance_city_date ON da_attendance (city_id, attendance_date, shift_type, status);

-- Shared trigger fn (already created by orders V4_2). CREATE OR REPLACE keeps this self-contained.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER da_attendance_updated_at
    BEFORE UPDATE ON da_attendance
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
