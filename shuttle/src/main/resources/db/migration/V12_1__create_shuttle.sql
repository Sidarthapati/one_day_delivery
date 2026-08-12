-- M12 shuttle: live GPS per agent (overwritten) + per-parcel leg binding (append-only).

CREATE TABLE shuttle_live_status (
    agent_id     uuid PRIMARY KEY,
    city_id      varchar(50),
    last_lat     double precision,
    last_lon     double precision,
    last_seen_at timestamptz,
    updated_at   timestamptz NOT NULL
);

CREATE TABLE shuttle_leg (
    id         uuid PRIMARY KEY,
    parcel_id  uuid NOT NULL,
    agent_id   uuid NOT NULL,
    direction  varchar(16) NOT NULL,
    bag_id     uuid,
    awb_id     uuid,
    created_at timestamptz NOT NULL
);

CREATE INDEX idx_shuttle_leg_parcel ON shuttle_leg (parcel_id, created_at DESC);
CREATE INDEX idx_shuttle_leg_awb ON shuttle_leg (awb_id) WHERE awb_id IS NOT NULL;
