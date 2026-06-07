-- M6-D-005/-019: per-city fleet + cycle/dwell knobs. Mutable (ops edits it) — not append-only.
CREATE TABLE city_fleet_config (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id                   UUID NOT NULL UNIQUE,
    vans_available            INT  NOT NULL,
    capacity_packets          INT  NOT NULL,
    cycle_time_min_minutes    INT  NOT NULL,
    cycle_time_max_minutes    INT  NOT NULL,
    shuttle_cadence_minutes   INT  NOT NULL,
    max_da_to_vertex_minutes  INT  NOT NULL,
    dwell_minutes             INT  NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
