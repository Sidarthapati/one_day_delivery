-- DA self-service disposition (breaks / auxiliary company-work / day-off). A DA raises a disposition:
--   BREAK    — personal (lunch/rest/EV-charging/…); auto-approved within his deadline-aware slots and
--              his 60-min/day allowance; holds his territory (no reassignment).
--   AUXILIARY— self-declared company work; manager-approved; holds territory; does NOT count allowance.
--   DAY_OFF  — out for the day; manager-approved → the existing absence reassignment.
-- An overstay escalates to the station manager (never auto-vacate). Mirrors da_absence_event style;
-- enum-like columns are VARCHAR + app-side @Enumerated(STRING).
CREATE TABLE da_disposition (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id             UUID        NOT NULL,
    city_id           UUID        NOT NULL,
    operating_date    DATE        NOT NULL,
    category          VARCHAR(20) NOT NULL,               -- BREAK | AUXILIARY | DAY_OFF
    reason            VARCHAR(30) NOT NULL,               -- LUNCH | REST | EV_CHARGING | COMPANY_WORK | OTHER
    note              VARCHAR(500),
    status            VARCHAR(20) NOT NULL,               -- PENDING | ACTIVE | COMPLETED | OVERSTAYED | REJECTED | CANCELLED
    duration_minutes  INT,                                -- requested minutes (BREAK/AUXILIARY); refined to actual on end
    scheduled_start   TIMESTAMPTZ,
    scheduled_end     TIMESTAMPTZ,
    actual_start      TIMESTAMPTZ,
    actual_end        TIMESTAMPTZ,
    counts_allowance  BOOLEAN     NOT NULL DEFAULT TRUE,  -- BREAK reasons count; AUXILIARY/DAY_OFF don't
    escalation_level  INT         NOT NULL DEFAULT 0,     -- bumped past scheduled_end → drives the in-app "break exceeded" banner
    created_by        UUID,
    approved_by       UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A DA's dispositions for a day (active-guard, allowance sum, driver-app card).
CREATE INDEX idx_da_disposition_da_date ON da_disposition (da_id, operating_date);
-- Manager view: pending requests + active/overstayed by city.
CREATE INDEX idx_da_disposition_city_status ON da_disposition (city_id, operating_date, status);
-- At most one live (PENDING/ACTIVE/OVERSTAYED) disposition per DA at a time — guards double-request races.
CREATE UNIQUE INDEX uq_da_disposition_live_per_da ON da_disposition (da_id)
    WHERE status IN ('PENDING', 'ACTIVE', 'OVERSTAYED');

-- Shared trigger fn (already created by orders V4_2). CREATE OR REPLACE keeps this self-contained.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER da_disposition_updated_at
    BEFORE UPDATE ON da_disposition
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
