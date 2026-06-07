-- M6-D-008: per-DA (vertex, [meeting_times]) for the day → DA_CRON_SCHEDULED to M5.
-- meeting_times is a JSON array of "HH:mm" strings (the day's loop arrivals at the DA's vertex).
CREATE TABLE da_cron_schedule (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_plan_id  UUID  NOT NULL REFERENCES route_plan(id),
    da_id          UUID  NOT NULL,
    hex_vertex_id  UUID  NOT NULL,
    van_id         UUID,
    meeting_times  JSONB NOT NULL,
    city_id        UUID  NOT NULL,
    valid_date     DATE  NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_da_cron_schedule_da_date ON da_cron_schedule (da_id, valid_date);
