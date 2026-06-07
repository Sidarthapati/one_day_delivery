-- M6-D-012: latest position + lateness per van, OVERWRITTEN in place (not append-only) — powers the
-- ops map. Raw GPS pings never land in Kafka; only this row is kept (overwrite).
CREATE TABLE van_live_status (
    van_id          UUID PRIMARY KEY,
    city_id         UUID NOT NULL,
    route_plan_id   UUID,
    last_lat        DOUBLE PRECISION,
    last_lon        DOUBLE PRECISION,
    last_seen_at    TIMESTAMPTZ,
    current_stop_seq INT,
    minutes_late    INT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_van_live_status_city ON van_live_status (city_id);
