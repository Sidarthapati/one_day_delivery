-- M11 attendance alerts: a station-manager inbox item raised by M5 when a rostered DA's hub proximity
-- could not be confirmed by the shift cutoff. Unlike exception_case (shipment-bound) this is DA+date
-- scoped. The manager settles it from the station console — mark present, or mark absent (which triggers
-- the DA-absence reassignment in M5). Enum-like columns are VARCHAR + app-side @Enumerated(STRING).
CREATE TABLE attendance_alert (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id             UUID NOT NULL,
    city_id           UUID NOT NULL,
    city_code         VARCHAR(40),                        -- grid code (e.g. "delhi") for city-scoped ops reads
    attendance_date   DATE NOT NULL,
    shift_type        VARCHAR(20),                        -- SHIFT_1 | SHIFT_2
    da_name           VARCHAR(120),
    status            VARCHAR(16) NOT NULL DEFAULT 'OPEN', -- OPEN | RESOLVED
    resolution        VARCHAR(16),                        -- PRESENT | ABSENT (when RESOLVED)
    resolved_at       TIMESTAMPTZ,
    resolved_by       VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attendance_alert_da_date UNIQUE (da_id, attendance_date)  -- dedupe re-emitted cutoffs
);

-- Ops queue: open alerts, freshest first. Partial index keeps the read over just the live set.
CREATE INDEX idx_attendance_alert_open ON attendance_alert (created_at DESC) WHERE status = 'OPEN';
