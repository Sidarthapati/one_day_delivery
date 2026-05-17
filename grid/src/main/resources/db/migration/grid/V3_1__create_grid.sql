CREATE TABLE grid (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id        UUID NOT NULL,
    origin_lat     DOUBLE PRECISION NOT NULL,
    origin_lon     DOUBLE PRECISION NOT NULL,
    tile_delta_lat DOUBLE PRECISION NOT NULL,
    tile_delta_lon DOUBLE PRECISION NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(city_id)
);
