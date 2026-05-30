CREATE TABLE h3_grid (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id       UUID NOT NULL,
    h3_resolution INT  NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(city_id)
);
