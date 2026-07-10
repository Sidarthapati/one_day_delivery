-- M7 §14.3, §8.2 — the destination mirror of bag_item: "parcel X is in delivery bag Y, on stand Z",
-- waiting for the van to load the whole bag (or the hub-collect shelf). The delivery_bag unit + the
-- delivery_bag_id FK land in PR #2; for now this records per-parcel membership. Status-mutable.
CREATE TABLE delivery_bag_item (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parcel_id   UUID         NOT NULL,
    shipment_ref VARCHAR(30) NOT NULL,
    city_id     UUID         NOT NULL,
    hub_id      UUID         NOT NULL,
    dest_hex_id UUID,
    stand_id    UUID         REFERENCES stand(id),
    drop_type   VARCHAR(16)  NOT NULL,            -- DA_DELIVERY | HUB_COLLECT
    loop_hint   INT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'STAGED',  -- STAGED|LOADED|ON_SHELF|COLLECTED
    staged_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_bag_item_city ON delivery_bag_item (city_id, status);
CREATE INDEX idx_delivery_bag_item_parcel ON delivery_bag_item (parcel_id);
