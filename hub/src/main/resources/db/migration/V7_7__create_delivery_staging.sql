-- M7 §14.3, §8.2 — the destination-hub handoff buffer: a parcel staged on a delivery stand by
-- drop-van loop, waiting for M6 to load it (or the hub-collect shelf). Populated in PR #2. Status-mutable.
CREATE TABLE delivery_staging (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parcel_id   UUID         NOT NULL,
    shipment_ref VARCHAR(30) NOT NULL,
    city_id     UUID         NOT NULL,
    hub_id      UUID         NOT NULL,
    dest_hex_id UUID,
    stand_id    UUID         REFERENCES stand(id),
    drop_type   VARCHAR(16)  NOT NULL,            -- DA_DELIVERY | HUB_COLLECT
    loop_hint   INT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'STAGED',  -- STAGED|HANDED_TO_VAN|ON_SHELF|COLLECTED
    staged_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_staging_city ON delivery_staging (city_id, status);
CREATE INDEX idx_delivery_staging_parcel ON delivery_staging (parcel_id);
