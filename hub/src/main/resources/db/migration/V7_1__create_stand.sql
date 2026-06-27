-- M7 §14.3 — a numbered physical spot on the hub floor (a shelf/cage). FLIGHT_BAG stands hold an
-- outbound bag; DELIVERY_STAGING stands hold last-mile parcels by loop/zone. Status-mutable.
CREATE TABLE stand (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id    UUID        NOT NULL,
    hub_id     UUID        NOT NULL,
    stand_no   VARCHAR(16) NOT NULL,
    zone       VARCHAR(32),
    kind       VARCHAR(20) NOT NULL,             -- FLIGHT_BAG | DELIVERY_STAGING
    capacity   INT         NOT NULL,
    status     VARCHAR(12) NOT NULL DEFAULT 'OPEN',  -- OPEN | FULL | CLOSED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_stand_no ON stand (hub_id, stand_no);
CREATE INDEX idx_stand_city_kind ON stand (city_id, kind);
