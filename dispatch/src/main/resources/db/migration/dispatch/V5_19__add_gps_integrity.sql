-- M5 dispatch — location-trust hardening (Phase 1). Carry per-ping integrity signals on the append-only
-- breadcrumb so a spoofed/implausible fix is detectable after the fact and can be excluded from
-- attendance/tracking. All columns nullable: pings from an app build that predates this stay valid.
--   mocked        : device reported the fix came from a mock-location provider (Android isMock)
--   accuracy_m    : device-reported horizontal accuracy (m); wildly large = low-trust
--   speed_mps     : device-reported speed (m/s), if any
--   velocity_flag : server found an impossible jump from the previous fix (teleport)
--   ts_skew_flag  : device fix time (recorded_at) is implausibly far from server receive time (created_at)
--   risk_score    : 0-100 rollup of the signals above (higher = less trustworthy)
ALTER TABLE da_gps_ping
    ADD COLUMN mocked        BOOLEAN,
    ADD COLUMN accuracy_m    DOUBLE PRECISION,
    ADD COLUMN speed_mps     DOUBLE PRECISION,
    ADD COLUMN velocity_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ts_skew_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN risk_score    INTEGER NOT NULL DEFAULT 0;

-- Ops query: find a DA's low-trust fixes fast (attendance review / fraud triage).
CREATE INDEX idx_da_gps_ping_risk ON da_gps_ping (da_id, recorded_at) WHERE risk_score > 0;
