CREATE TABLE tile_travel_time (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id             UUID NOT NULL REFERENCES grid(id),
    from_tile_id        UUID NOT NULL REFERENCES tile(id),
    to_tile_id          UUID NOT NULL REFERENCES tile(id),
    travel_time_seconds INT NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(grid_id, from_tile_id, to_tile_id)
);
