-- M7 §14.3, §7.2 — the consolidation unit: one bag per (flight_no, flight_date, dest_hub), sited on
-- a stand. Status-mutable (OPEN→SEALED→DISPATCHED→HANDED_OVER); contents are append-only via flight_bag_item.
CREATE TABLE flight_bag (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id          UUID         NOT NULL,
    hub_id           UUID         NOT NULL,
    flight_no        VARCHAR(20)  NOT NULL,
    flight_date      DATE         NOT NULL,
    origin_hub       VARCHAR(10)  NOT NULL,
    dest_hub         VARCHAR(10)  NOT NULL,
    current_stand_id UUID         NOT NULL REFERENCES stand(id),
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN',   -- OPEN|SEALED|DISPATCHED|HANDED_OVER
    parcel_count     INT          NOT NULL DEFAULT 0,
    weight_grams     INT          NOT NULL DEFAULT 0,
    bag_cutoff       TIMESTAMPTZ,
    manifest_id      UUID,
    sealed_at        TIMESTAMPTZ,
    dispatched_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One open bag per (flight, date, dest_hub) — the lazy-create lookup key.
CREATE UNIQUE INDEX uq_flight_bag_open
    ON flight_bag (flight_no, flight_date, dest_hub) WHERE status = 'OPEN';
CREATE INDEX idx_flight_bag_city ON flight_bag (city_id, flight_date);
CREATE INDEX idx_flight_bag_stand ON flight_bag (current_stand_id);
