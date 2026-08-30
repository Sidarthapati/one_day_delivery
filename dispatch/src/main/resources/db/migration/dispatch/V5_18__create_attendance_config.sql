-- Global, ops-tunable attendance config (a single row). v1 holds one flag: auto_present_enabled —
-- the master switch for geofence auto-present in AttendanceServiceImpl.onGpsFix. When OFF, a GPS fix
-- inside the hub geofence no longer auto-marks a DA present; the manual "I've arrived" check-in still
-- works. Distinct from the yaml knobs in DispatchProperties.Attendance (radius/zone) which are static.
-- (V5_17 is reserved by the concurrent delivery-outcomes branch — this lands at V5_18 to avoid a clash.)
CREATE TABLE attendance_config (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auto_present_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by_user_id    UUID,                                   -- the ADMIN who last flipped it; null for the seed
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed the singleton row (enabled — preserves today's behaviour until an admin turns it off).
INSERT INTO attendance_config (auto_present_enabled) VALUES (TRUE);

-- Shared trigger fn (created by earlier migrations). CREATE OR REPLACE keeps this self-contained.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER attendance_config_updated_at
    BEFORE UPDATE ON attendance_config
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
