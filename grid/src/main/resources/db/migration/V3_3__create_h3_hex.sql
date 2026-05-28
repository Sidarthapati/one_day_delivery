CREATE TABLE h3_hex (
    id            UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    h3_grid_id    UUID    NOT NULL REFERENCES h3_grid(id),
    h3_index      BIGINT  NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT false,
    traversal_cap_sec INT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(h3_grid_id, h3_index)
);
