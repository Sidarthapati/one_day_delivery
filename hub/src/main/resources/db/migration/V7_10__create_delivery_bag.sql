-- M7 §14.3, §8.1 (M7-D-012) — the DESTINATION consolidation unit, mirror of flight_bag. A delivery
-- bag groups the parcels a single last-mile van loop (ROUTE) — or a DA territory the DA hub-collects
-- (DA_TERRITORY), or an M3 zone when territories overflow the stand pool (ZONE) — carries away. It is
-- sited on a stand from the SAME shared pool as flight bags (dynamic allocation, M7-D-001). One open
-- bag per route/territory/zone key per day; status-mutable, contents append-only via delivery_bag_item.
CREATE TABLE delivery_bag (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id          UUID         NOT NULL,
    hub_id           UUID         NOT NULL,
    bag_kind         VARCHAR(16)  NOT NULL,             -- ROUTE | DA_TERRITORY | ZONE
    bag_date         DATE         NOT NULL,
    route_plan_id    UUID,                              -- ROUTE: M6 nightly plan the loop belongs to
    loop_id          UUID,                              -- ROUTE: the van loop (key); else NULL
    da_territory_id  UUID,                              -- DA_TERRITORY: the DA whose territory (key); else NULL
    zone_id          UUID,                              -- ZONE: the M3 zone (key); else NULL
    current_stand_id UUID         NOT NULL REFERENCES stand(id),
    status           VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN|SEALED|LOADED|HANDED_OVER
    parcel_count     INT          NOT NULL DEFAULT 0,
    weight_grams     INT          NOT NULL DEFAULT 0,
    manifest_id      UUID,
    sealed_at        TIMESTAMPTZ,
    loaded_at        TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One open bag per key, scoped by kind (the lazy-create lookup, mirror of uq_flight_bag_open).
CREATE UNIQUE INDEX uq_delivery_bag_open_route
    ON delivery_bag (loop_id, bag_date) WHERE status = 'OPEN' AND bag_kind = 'ROUTE';
CREATE UNIQUE INDEX uq_delivery_bag_open_territory
    ON delivery_bag (da_territory_id, bag_date) WHERE status = 'OPEN' AND bag_kind = 'DA_TERRITORY';
CREATE UNIQUE INDEX uq_delivery_bag_open_zone
    ON delivery_bag (zone_id, bag_date) WHERE status = 'OPEN' AND bag_kind = 'ZONE';
CREATE INDEX idx_delivery_bag_city ON delivery_bag (city_id, bag_date);
CREATE INDEX idx_delivery_bag_stand ON delivery_bag (current_stand_id);
