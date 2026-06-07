-- M6-D-007: hub & airport coordinates, which no module stored before. Plain city_id UUID
-- (shared with M3's grid.cities config) — no cross-module FK, mirroring da_hex_assignment.city_id.
CREATE TABLE city_logistics_node (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id     UUID         NOT NULL,
    kind        VARCHAR(10)  NOT NULL,            -- HUB | AIRPORT
    lat         DOUBLE PRECISION NOT NULL,
    lon         DOUBLE PRECISION NOT NULL,
    name        VARCHAR(120) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (city_id, kind)
);

CREATE INDEX idx_logistics_node_city ON city_logistics_node (city_id);
