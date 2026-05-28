CREATE TABLE h3_hex_travel_time (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    h3_grid_id          UUID NOT NULL REFERENCES h3_grid(id),
    from_hex_id         UUID NOT NULL REFERENCES h3_hex(id),
    to_hex_id           UUID NOT NULL REFERENCES h3_hex(id),
    travel_time_seconds INT  NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(h3_grid_id, from_hex_id, to_hex_id)
);
