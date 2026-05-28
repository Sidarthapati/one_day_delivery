CREATE TABLE h3_hex_vertex (
    id               UUID   PRIMARY KEY DEFAULT gen_random_uuid(),
    h3_grid_id       UUID   NOT NULL REFERENCES h3_grid(id),
    h3_vertex_index  BIGINT NOT NULL,
    lat              DOUBLE PRECISION NOT NULL,
    lon              DOUBLE PRECISION NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(h3_grid_id, h3_vertex_index)
);
