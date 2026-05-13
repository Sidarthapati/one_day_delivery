CREATE TABLE grid_vertex (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id UUID NOT NULL REFERENCES grid(id),
    row_idx INT NOT NULL,
    col_idx INT NOT NULL,
    lat     DOUBLE PRECISION NOT NULL,
    lon     DOUBLE PRECISION NOT NULL,
    UNIQUE(grid_id, row_idx, col_idx)
);
