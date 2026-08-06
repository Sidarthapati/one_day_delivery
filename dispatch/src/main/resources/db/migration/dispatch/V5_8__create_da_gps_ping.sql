-- M5 dispatch: append-only GPS breadcrumb trail — one row per received DA ping.
-- Unlike da_status (single row per DA, latest-only, overwrite-in-place), this keeps EVERY fix so we
-- can answer "where has this DA been" / replay a route. Never updated (append-only, extends BaseEntity),
-- so no set_updated_at trigger — updated_at just stays equal to created_at.
CREATE TABLE da_gps_ping (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity @CreationTimestamp (server receive time)
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),  -- BaseEntity; stays == created_at (append-only)
    da_id        UUID NOT NULL,
    lat          DOUBLE PRECISION NOT NULL,
    lon          DOUBLE PRECISION NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL                 -- device fix time (client timestamp on the ping)
);

-- Hot path: fetch one DA's trail over a time window, in chronological order (route replay / ops query).
CREATE INDEX idx_da_gps_ping_da_time ON da_gps_ping (da_id, recorded_at);
