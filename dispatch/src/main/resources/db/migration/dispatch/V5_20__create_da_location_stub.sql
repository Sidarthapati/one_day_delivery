-- M5 dispatch: a DA's "location stub" — one row per VISIT to a physical location on a shift.
-- A stub is a ticket: "what to collect/deliver at this doorstep right now." It groups the DA's tasks at
-- one location (dispatch_queue.stub_id back-ref) and measures dwell (time-at-location) as:
--   first "Mark arrived" → last task completed at the location.
-- OPEN while shipments are being worked; CLOSED once all are done. A later return to the SAME location
-- opens a NEW stub (partial-unique on status='OPEN'), so a 12:05 and an 18:00 visit are separate tickets.
CREATE TABLE da_location_stub (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @CreationTimestamp
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),  -- MutableBaseEntity; bumped by set_updated_at()
    da_id                   UUID NOT NULL,
    city_id                 UUID NOT NULL,
    operating_date          DATE NOT NULL,
    location_key            VARCHAR(40) NOT NULL,   -- "lat,lon" rounded to 5dp (~1.1m); matches the DA-app grouping
    stub_lat                DOUBLE PRECISION NOT NULL,
    stub_lon                DOUBLE PRECISION NOT NULL,
    tile_id                 UUID,                   -- coarser H3 key for later hex-level rollups
    status                  VARCHAR(12) NOT NULL DEFAULT 'OPEN',  -- OPEN | CLOSED
    opened_at               TIMESTAMPTZ,            -- first task attached
    closed_at               TIMESTAMPTZ,            -- set when the last shipment in the visit finishes
    task_count              INT NOT NULL DEFAULT 0, -- total shipments/tasks in the visit (the ticket size)
    processed_count         INT NOT NULL DEFAULT 0, -- tasks that completed
    failed_count            INT NOT NULL DEFAULT 0, -- tasks that failed
    first_arrived_at        TIMESTAMPTZ,            -- earliest "Mark arrived" across the stub's tasks
    last_completed_at       TIMESTAMPTZ,            -- latest task completion in the visit
    dwell_seconds           BIGINT                  -- time-at-location: last_completed_at - first_arrived_at
);

-- At most one OPEN visit per (DA, day, location): find-or-create joins the open one; a closed stub frees
-- the key so the next task at that spot opens a fresh visit. Partial-unique — the same idiom dispatch_queue
-- uses to allow re-assignment after a FAILED/CANCELLED attempt.
CREATE UNIQUE INDEX uq_da_location_stub_open
    ON da_location_stub (da_id, operating_date, location_key) WHERE status = 'OPEN';

-- Read paths: a DA's visits for a day (app + station DA-detail); a city's visits for a day (ops rollup).
CREATE INDEX idx_da_location_stub_da_day ON da_location_stub (da_id, operating_date);
CREATE INDEX idx_da_location_stub_city_day ON da_location_stub (city_id, operating_date);

-- Shared trigger fn (already created by orders V4_2 / da_status V5_3). CREATE OR REPLACE keeps this
-- migration self-contained regardless of cross-module run order on the shared DB.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER da_location_stub_updated_at
    BEFORE UPDATE ON da_location_stub
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Back-ref: each queue task points at the location-stub visit it belongs to. Nullable bare UUID (no FK),
-- same cross-module convention as order_id (V5_13). Set once at assignment.
ALTER TABLE dispatch_queue ADD COLUMN stub_id UUID;
CREATE INDEX idx_dispatch_queue_stub_id ON dispatch_queue (stub_id);
