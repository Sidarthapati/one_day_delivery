-- M5 dispatch: one row per DA — the ONLY fully-mutable M5 table. Authoritative state lives
-- in-memory and is flushed here every ~2 min (GPS, status, queue depth). updated_at is bumped by
-- the shared set_updated_at() trigger.
CREATE TABLE da_status (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    da_id            UUID NOT NULL UNIQUE,
    city_id          UUID NOT NULL,
    shift_date       DATE NOT NULL,
    shift_type       VARCHAR(20),
    status           VARCHAR(20) NOT NULL DEFAULT 'OFFLINE', -- OFFLINE|IDLE|IN_PROGRESS|CRON_LOCKED|AT_CRON|ABSENT
    last_gps_lat     DOUBLE PRECISION,
    last_gps_lon     DOUBLE PRECISION,
    current_tile_id  UUID,
    queue_depth      INT NOT NULL DEFAULT 0,
    last_heartbeat   TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hot path: list a city's DAs for a shift by status (shift load, absent detection, station view).
CREATE INDEX idx_da_status_city_shift ON da_status (city_id, shift_date, status);

-- Shared trigger fn (already created by orders V4_2). CREATE OR REPLACE keeps this migration
-- self-contained regardless of cross-module run order on the shared DB.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER da_status_updated_at
    BEFORE UPDATE ON da_status
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
