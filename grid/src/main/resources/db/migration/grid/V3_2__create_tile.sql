CREATE TABLE tile (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grid_id           UUID NOT NULL REFERENCES grid(id),
    row_idx           INT NOT NULL,
    col_idx           INT NOT NULL,
    is_active         BOOLEAN NOT NULL DEFAULT false,
    traversal_cap_sec INT,
    UNIQUE(grid_id, row_idx, col_idx)
);
